package net.discdd.bundledropbox.store;

import java.util.Optional;

/**
 * Holds the server-signed recency blob the dropbox relays to clients.
 *
 * <p>Why this matters: the stock client aborts the whole exchange with {@code RecencyException} unless
 * {@code getRecencyBlob} returns status {@code RECENCY_BLOB_STATUS_SUCCESS} with a valid server
 * signature (Research/R1 §3.1, R3). The dropbox is offline and can never mint one, so it copies the
 * genuine blob from a carrier during each VISIT and serves it verbatim in HOST mode and in the DNS-SD
 * TXT {@code recencyBlob} key. Until it has one, the dropbox must not HOST (bootstrap rule).
 *
 * <p>Never fabricate or re-sign a blob. Persisted at {@link DropboxPaths#recencyBlobFile} by
 * {@code store/FileRecencyBlobStore}. Thread-safe.
 */
public interface RecencyBlobStore {
    /**
     * @return the raw serialized {@code net.discdd.grpc.GetRecencyBlobResponse} bytes last accepted by
     *         {@link #offer(byte[])}, or empty if the dropbox has never received a valid blob
     */
    Optional<byte[]> current();

    /**
     * Offers a {@code GetRecencyBlobResponse} fetched from a carrier.
     *
     * <p>It is stored (atomically, replacing the old one) only if it parses, its status is
     * {@code RECENCY_BLOB_STATUS_SUCCESS}, and its {@code recencyBlob.blobTimestamp} is strictly newer
     * than {@link #currentTimestamp()}. A transport that never reached the server returns the default
     * instance (status UNDEFINED); that must never overwrite a good blob.
     *
     * @param rawResponse serialized {@code GetRecencyBlobResponse}, exactly as received
     * @return true if it was stored
     */
    boolean offer(byte[] rawResponse);

    /**
     * @return the stored blob's {@code blobTimestamp} (server wall clock, epoch millis), or 0 if none
     */
    long currentTimestamp();
}
