package org.whispersystems.signalservice.api.push;

import com.google.protobuf.ByteString;

import org.whispersystems.signalservice.api.util.UuidUtil;

import java.util.UUID;

/**
 * An ACI is an "Account Identity". They're just UUIDs, but given multiple different things could be UUIDs, this wrapper exists to give us type safety around
 * this *specific type* of UUID.
 */
public final class ACI extends ServiceId {

  public static ACI from(UUID uuid) {
    return new ACI(uuid);
  }

  public static ACI from(ServiceId serviceId) {
    return new ACI(serviceId.uuid());
  }

  public static ACI fromNullable(ServiceId serviceId) {
    return serviceId != null ? new ACI(serviceId.uuid()) : null;
  }

  public static ACI parseOrThrow(String raw) {
    return from(UUID.fromString(raw));
  }

  public static ACI parseOrNull(String raw) {
    UUID uuid = UuidUtil.parseOrNull(raw);
    return uuid != null ? from(uuid) : null;
  }

  private ACI(UUID uuid) {
    super(uuid);
  }

  public ByteString toByteString() {
    return UuidUtil.toByteString(uuid);
  }

  public byte[] toByteArray() {
    return UuidUtil.toByteArray(uuid);
  }
}
