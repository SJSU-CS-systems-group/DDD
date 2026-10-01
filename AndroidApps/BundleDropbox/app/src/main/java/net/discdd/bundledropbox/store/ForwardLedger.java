package net.discdd.bundledropbox.store;

/**
 * Remembers which UPSTREAM bundles have already been handed to which carrier (BundleTransport), so a
 * visit only uploads what that carrier does not have yet.
 *
 * <p>Upstream bundles are never deleted in v1 (no keep/delete loop), so without this ledger every
 * visit would re-upload the whole queue. Different carriers are tracked separately because each one
 * reaches the server independently; uploading to all of them maximizes delivery odds.
 *
 * <p>The carrier id is the transport's advertised DNS-SD {@code transportId} (Research/R1 §2.4).
 * Implementations must be thread-safe and persistent across restarts ({@code store/JsonForwardLedger}).
 */
public interface ForwardLedger {
    /**
     * @param bundleId  encrypted bundle id of an UPSTREAM bundle
     * @param carrierId the carrier's {@code transportId}
     * @return true if {@link #recordForwarded} was called for this pair
     */
    boolean wasForwardedTo(String bundleId, String carrierId);

    /**
     * Records that the carrier acknowledged the upload ({@code BundleUploadResponse} status SUCCESS).
     * Call only after the acknowledgement, never before, so failed uploads are retried.
     *
     * @param bundleId  encrypted bundle id of an UPSTREAM bundle
     * @param carrierId the carrier's {@code transportId}
     * @param atMillis  wall-clock time of the acknowledgement
     */
    void recordForwarded(String bundleId, String carrierId, long atMillis);
}
