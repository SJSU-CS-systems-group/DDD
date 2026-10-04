package net.discdd.bundledropbox.store;

import com.google.protobuf.InvalidProtocolBufferException;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.RecencyBlobStatus;

import java.util.Optional;

/**
 * Holds the server-signed recency blob the dropbox relays to clients.
 *
 * <p>Why this matters: the stock client aborts the whole exchange with {@code RecencyException} unless
 * {@code getRecencyBlob} returns status {@code RECENCY_BLOB_STATUS_SUCCESS} with a valid signature from the
 * server it trusts and a timestamp no more than 60 s in the future (Research/R1 §3.1, R3). The dropbox is
 * offline and can never mint one, so it copies the genuine blob from a carrier during each VISIT and serves it
 * verbatim in HOST mode and in the DNS-SD TXT {@code recencyBlob} key. Until it has one, the dropbox must not
 * HOST (bootstrap rule).
 *
 * <p>Never fabricate or re-sign a blob, and never store one the clients would reject (see
 * {@link RecencyBlobVerifier}). Persisted at {@link DropboxPaths#recencyBlobFile} by
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
     * <p>It is stored (atomically, replacing the old one) only if it parses, a stock client would accept it
     * ({@link #isTrusted}: SUCCESS, trusted server key, valid signature, not more than 60 s in the future),
     * and its {@code recencyBlob.blobTimestamp} is strictly newer than {@link #currentTimestamp()}. A
     * transport that never reached the server returns the default instance (status UNDEFINED); that, a blob
     * from another deployment's server, or a forged one must never overwrite a good blob.
     *
     * @param rawResponse serialized {@code GetRecencyBlobResponse}, exactly as received
     * @return true if it was stored
     */
    boolean offer(byte[] rawResponse);

    /**
     * @param rawResponse serialized {@code GetRecencyBlobResponse}
     * @return true if a stock client of this dropbox would accept the blob (whether or not it is newer than
     *         the stored one). The visit uses this to tell a carrier synced with OUR server from one that never
     *         synced or belongs to another deployment. The default only checks status SUCCESS with a blob;
     *         {@code FileRecencyBlobStore} applies its {@link RecencyBlobVerifier}.
     */
    default boolean isTrusted(byte[] rawResponse) {
        if (rawResponse == null || rawResponse.length == 0) return false;
        try {
            GetRecencyBlobResponse r = GetRecencyBlobResponse.parseFrom(rawResponse);
            return r.getStatus() == RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS && r.hasRecencyBlob();
        } catch (InvalidProtocolBufferException e) {
            return false;
        }
    }

    /**
     * @return the stored blob's {@code blobTimestamp} (server wall clock, epoch millis), or 0 if none
     */
    long currentTimestamp();
}
