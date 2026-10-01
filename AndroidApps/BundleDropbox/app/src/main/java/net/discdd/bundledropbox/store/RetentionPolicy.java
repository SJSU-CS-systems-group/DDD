package net.discdd.bundledropbox.store;

/**
 * Decides when relayed bundles may be removed from the {@link RelayStore}.
 *
 * <p>v1: {@code KeepAllRetentionPolicy} never deletes anything. The dropbox cannot tell whether an
 * upstream bundle reached the server or a downstream bundle reached its client, so keeping everything
 * (bounded by the {@code max_storage_mb} capacity) is the only safe choice for the prototype.
 *
 * <p>Intentionally has no methods yet; it marks where the storage-management decision will plug in.
 *
 * FUTURE: the server manages all dropbox storage via specialized keep/delete control ADUs, informed by
 * client ACK reports ({@link DeliveryLog}). The policy will then select bundles to drop and call
 * {@link RelayStore#delete(Direction, String)}.
 */
public interface RetentionPolicy {
}
