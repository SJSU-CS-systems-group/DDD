package net.discdd.bundledropbox.store;

import net.discdd.grpc.GetRecencyBlobResponse;

/**
 * Decides whether a carrier's recency blob is one the dropbox's stock clients would accept.
 *
 * <p>Why the dropbox must check this itself: it relays the blob verbatim, and every stock client runs
 * {@code ClientBundleTransmission.processRecencyBlob} on it before any download or upload. A blob that fails
 * there aborts the client's whole exchange and still stamps {@code lastExchange}. If the dropbox stored such
 * a blob (from a carrier synced to another deployment, or a forged far-future one), it would lock out every
 * client until a "strictly newer" good blob arrived, which for a far-future timestamp is never
 * (Audit M5 {@code recency-blob-not-pinned-to-server}, M6 {@code recency-blob-poisoning}).
 *
 * <p>The production implementation is {@link ServerTrust}, built by the service from the server identity
 * key the dropbox's clients trust. Implementations must be thread-safe and side-effect free.
 */
public interface RecencyBlobVerifier {
    /**
     * @param response a parsed {@code getRecencyBlob} response
     * @return {@code null} if a stock client would accept it, otherwise a short human-readable reason
     */
    String rejectionReason(GetRecencyBlobResponse response);
}
