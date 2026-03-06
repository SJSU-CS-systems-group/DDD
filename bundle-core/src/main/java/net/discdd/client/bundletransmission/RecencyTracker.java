package net.discdd.client.bundletransmission;

import net.discdd.grpc.GetRecencyBlobResponse;

/**
 * Interface for tracking recency of transport exchanges.
 * Implementations determine if a recency blob is newer than previously seen.
 */
public interface RecencyTracker {
    /**
     * Check if the recency blob is newer than what was previously seen for this device.
     *
     * @param device   the transport device
     * @param response the recency blob response
     * @return true if this blob is more recent than previously seen
     */
    boolean isNewerRecencyBlob(TransportDevice device, GetRecencyBlobResponse response);
}