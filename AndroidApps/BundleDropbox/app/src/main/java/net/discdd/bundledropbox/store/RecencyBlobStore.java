package net.discdd.bundledropbox.store;

import com.google.protobuf.InvalidProtocolBufferException;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.RecencyBlobStatus;

import java.util.Optional;

/**
 * Holds the dropbox's <em>own</em> server-signed recency blob, the one it serves to clients.
 *
 * <p>Why this matters: the stock client aborts the whole exchange with {@code RecencyException} unless
 * {@code getRecencyBlob} returns status {@code RECENCY_BLOB_STATUS_SUCCESS} with a valid signature from the
 * server it trusts and a timestamp no more than 60 s in the future (Research/R1 §3.1, R3). The dropbox is
 * offline and can never mint one, so it asks the server for one through the control channel: it sends a signed
 * {@code RecencyRequest} for its own TLS key, and the server answers with a {@code RecencyGrant} whose blob names
 * the dropbox's own {@code senderId} (09 Own Recency Blob Design). The dropbox serves that blob verbatim in HOST
 * mode and in the DNS-SD TXT {@code recencyBlob} key. Until it has one, the dropbox must not HOST (bootstrap
 * rule).
 *
 * <p>Never fabricate or re-sign a blob, never store one the clients would reject (see
 * {@link RecencyBlobVerifier}), and never store a carrier transport's blob: it names the carrier, not the
 * dropbox. Persisted at {@link DropboxPaths#recencyBlobFile} by {@code store/FileRecencyBlobStore}. Thread-safe.
 */
public interface RecencyBlobStore {
    /**
     * @return the raw serialized {@code net.discdd.grpc.GetRecencyBlobResponse} bytes last accepted by
     *         {@link #offer(byte[])}, or empty if the dropbox has never received a valid blob of its own
     */
    Optional<byte[]> current();

    /**
     * Offers a {@code GetRecencyBlobResponse} the server granted the dropbox (a decoded {@code RecencyGrant}).
     *
     * <p>It is stored (atomically, replacing the old one) only if it parses, a stock client would accept it
     * ({@link #isServerSigned}: SUCCESS, trusted server key, valid signature, not more than 60 s in the future),
     * its {@code recencyBlob.senderId} is the dropbox's own sender id, and its {@code blobTimestamp} is strictly
     * newer than {@link #currentTimestamp()}. A carrier's blob (another {@code senderId}), a blob from another
     * deployment's server, or a forged one must never overwrite a good blob.
     *
     * @param rawResponse serialized {@code GetRecencyBlobResponse}, exactly as granted
     * @return true if it was stored
     */
    boolean offer(byte[] rawResponse);

    /**
     * @param rawResponse serialized {@code GetRecencyBlobResponse}
     * @return true if a stock client of this dropbox would accept the blob, whatever its {@code senderId} and
     *         whether or not it is newer than the stored one. The visit uses this to tell a carrier synced with
     *         OUR server from one that never synced or belongs to another deployment; such a blob is never
     *         stored. The default only checks status SUCCESS with a blob; {@code FileRecencyBlobStore} applies
     *         its {@link RecencyBlobVerifier}.
     */
    default boolean isServerSigned(byte[] rawResponse) {
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
