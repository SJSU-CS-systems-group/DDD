package org.whispersystems.signalservice.api.messages.multidevice;



import org.whispersystems.signalservice.api.push.ServiceId;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;

import java.util.Optional;

public class MessageRequestResponseMessage {

  private final Optional<ServiceId> person;
  private final Optional<byte[]>    groupId;
  private final Type                type;

  public static MessageRequestResponseMessage forIndividual(ServiceId address, Type type) {
    return new MessageRequestResponseMessage(Optional.of(address), Optional.empty(), type);
  }

  public static MessageRequestResponseMessage forGroup(byte[] groupId, Type type) {
    return new MessageRequestResponseMessage(Optional.empty(), Optional.of(groupId), type);
  }

  private MessageRequestResponseMessage(Optional<ServiceId> person,
                                        Optional<byte[]> groupId,
                                        Type type)
  {
    this.person  = person;
    this.groupId = groupId;
    this.type    = type;
  }

  public Optional<ServiceId> getPerson() {
    return person;
  }

  public Optional<byte[]> getGroupId() {
    return groupId;
  }

  public Type getType() {
    return type;
  }

  public enum Type {
    UNKNOWN, ACCEPT, DELETE, BLOCK, BLOCK_AND_DELETE, UNBLOCK_AND_ACCEPT
  }
}
