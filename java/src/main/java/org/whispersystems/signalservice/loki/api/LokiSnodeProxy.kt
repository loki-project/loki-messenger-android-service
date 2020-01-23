package org.whispersystems.signalservice.loki.api

import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.functional.map
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.whispersystems.curve25519.Curve25519
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.signalservice.internal.util.Base64
import org.whispersystems.signalservice.internal.util.Hex
import org.whispersystems.signalservice.internal.util.JsonUtil
import org.whispersystems.signalservice.loki.crypto.DiffieHellman

internal class LokiSnodeProxy(private val target: LokiAPITarget, timeout: Long) : LokiHTTPClient(timeout) {

    private val keyPair by lazy { curve.generateKeyPair() }

    // region Settings
    companion object {
        private val curve = Curve25519.getInstance(Curve25519.BEST)
    }
    // endregion

    // region Error
    sealed class Error(val description: String) : Exception() {
        class TargetPublicKeySetMissing(target: LokiAPITarget) : Error("Missing public key set for: $target.")
        object FailedToBuildRequestBody : Error("Failed to build request body")
    }
    // endregion

    override fun execute(request: Request): Promise<Response, Exception> {
        val targetHexEncodedPublicKeySet = target.publicKeySet ?: return Promise.ofFail(Error.TargetPublicKeySetMissing(target))
        val symmetricKey = curve.calculateAgreement(Hex.fromStringCondensed(targetHexEncodedPublicKeySet.encryptionKey), keyPair.privateKey)
        val requestBodyAsString = getBodyAsString(request)
        val canonicalRequestHeaders = getCanonicalHeaders(request)
        lateinit var proxy: LokiAPITarget
        return LokiSwarmAPI.getRandomSnode().bind { p ->
            proxy = p
            val url = "${proxy.address}:${proxy.port}/proxy"
            Log.d("LokiSnodeProxy", "Proxying request to $target through $proxy.")
            val unencryptedProxyRequestBody = mapOf( "method" to request.method(), "body" to requestBodyAsString, "headers" to canonicalRequestHeaders )
            val ivAndCipherText = DiffieHellman.encrypt(JsonUtil.toJson(unencryptedProxyRequestBody).toByteArray(Charsets.UTF_8), symmetricKey)
            val proxyRequest = Request.Builder()
                .url(url)
                .post(RequestBody.create(MediaType.get("application/octet-stream"), ivAndCipherText))
                .header("X-Sender-Public-Key", Hex.toStringCondensed(keyPair.publicKey))
                .header("X-Target-Snode-Key", targetHexEncodedPublicKeySet.idKey)
                .build()
            execute(proxyRequest, getClearnetConnection())
        }.map { response ->
            if (response.code() == 404) {
                // Prune snodes that don't implement the proxying endpoint
                LokiSwarmAPI.randomSnodePool.remove(proxy)
            }

            // Extract the body if possible
            var statusCode = response.code()
            var bodyAsString: String? = response.body()?.string()
            if (response.isSuccessful && bodyAsString != null) {
                val cipherText = Base64.decode(bodyAsString)
                val decrypted = DiffieHellman.decrypt(cipherText, symmetricKey)
                val responseBody = decrypted.toString(Charsets.UTF_8)
                val json = JsonUtil.fromJson(responseBody)
                statusCode = json.get("status").asInt()
                if (json.hasNonNull("body")) {
                    bodyAsString = json.get("body").asText()
                }
            }
            return@map Response(statusCode in 200..299, statusCode, bodyAsString)
        }
    }
    // endregion
}