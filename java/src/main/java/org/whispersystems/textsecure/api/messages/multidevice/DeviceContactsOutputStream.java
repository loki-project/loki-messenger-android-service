package org.whispersystems.textsecure.api.messages.multidevice;

import org.whispersystems.textsecure.internal.push.SignalServiceProtos;

import java.io.IOException;
import java.io.OutputStream;

public class DeviceContactsOutputStream extends ChunkedOutputStream {

  public DeviceContactsOutputStream(OutputStream out) {
    super(out);
  }

  public void write(DeviceContact contact) throws IOException {
    writeContactDetails(contact);
    writeAvatarImage(contact);
  }

  public void close() throws IOException {
    out.close();
  }

  private void writeAvatarImage(DeviceContact contact) throws IOException {
    if (contact.getAvatar().isPresent()) {
      writeStream(contact.getAvatar().get().getInputStream());
    }
  }

  private void writeContactDetails(DeviceContact contact) throws IOException {
    SignalServiceProtos.ContactDetails.Builder contactDetails = SignalServiceProtos.ContactDetails.newBuilder();
    contactDetails.setNumber(contact.getNumber());

    if (contact.getName().isPresent()) {
      contactDetails.setName(contact.getName().get());
    }

    if (contact.getAvatar().isPresent()) {
      SignalServiceProtos.ContactDetails.Avatar.Builder avatarBuilder = SignalServiceProtos.ContactDetails.Avatar.newBuilder();
      avatarBuilder.setContentType(contact.getAvatar().get().getContentType());
      avatarBuilder.setLength((int)contact.getAvatar().get().getLength());
      contactDetails.setAvatar(avatarBuilder);
    }

    byte[] serializedContactDetails = contactDetails.build().toByteArray();

    writeVarint32(serializedContactDetails.length);
    out.write(serializedContactDetails);
  }

}
