package net.discdd.bundledropbox.store;

import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.RecencyBlob;
import net.discdd.grpc.RecencyBlobStatus;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECPublicKey;

import java.util.Base64;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The dropbox's pinned server identity: accepts exactly the recency blobs a stock BundleClient configured
 * for the same server accepts.
 *
 * <p>{@link #rejectionReason} mirrors {@code ClientBundleTransmission.processRecencyBlob} (bundle-core) check
 * for check and in the same order:
 * <ol>
 *   <li>status must be {@code RECENCY_BLOB_STATUS_SUCCESS} ("Recency request failed");</li>
 *   <li>{@code blobTimestamp <= now + 60 s} ("timestamp is in the future"; the client allows one minute
 *       of clock skew);</li>
 *   <li>{@code Curve.decodePoint(serverPublicKey)} must equal the trusted server identity key
 *       ("signed by unknown server");</li>
 *   <li>{@code SecurityUtils.verifySignatureRaw(recencyBlob.toByteArray(), key, recencyBlobSignature)}
 *       ("signature verification failed").</li>
 * </ol>
 * Anything the client would reject is rejected here, so the dropbox never stores or advertises a blob that
 * aborts its clients' exchanges (Audit M5/M6). The key is the same one the clients pin: the service passes
 * {@code ServerKeys.identityKey()}, i.e. android-core's {@code server_identity.pub} raw resource or an
 * operator-imported key (custom server keys via QR, 06 Storage Management Design).
 *
 * <p>Known limit: the future bound uses the dropbox's own clock. An offline dropbox whose clock runs more
 * than 60 s slow rejects fresh genuine blobs; its clients would reject them too if their clocks were equally
 * slow, but clients with correct clocks would not. The rejection reason is logged by the store so the
 * operator can spot it.
 *
 * <p>Immutable and thread-safe. Pure Java (libsignal's Java curve), JVM-testable.
 */
public final class ServerTrust implements RecencyBlobVerifier {
    /** Allowed clock skew for blob timestamps, the same minute the stock client allows. */
    public static final long MAX_FUTURE_SKEW_MS = 60 * 1000;

    private final ECPublicKey serverIdentityKey;
    private final LongSupplier wallClockMillis;

    /**
     * @param serverIdentityKey the server identity public key the dropbox's clients trust
     * @param wallClockMillis   wall clock (epoch millis) for the future-timestamp bound
     */
    public ServerTrust(ECPublicKey serverIdentityKey, LongSupplier wallClockMillis) {
        this.serverIdentityKey = Objects.requireNonNull(serverIdentityKey, "serverIdentityKey");
        this.wallClockMillis = Objects.requireNonNull(wallClockMillis, "wallClockMillis");
    }

    /** @return the pinned server identity key. */
    public ECPublicKey serverIdentityKey() {
        return serverIdentityKey;
    }

    @Override
    public String rejectionReason(GetRecencyBlobResponse response) {
        if (response == null) return "no response";
        if (response.getStatus() != RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS || !response.hasRecencyBlob()) {
            return "status " + response.getStatus();
        }
        RecencyBlob blob = response.getRecencyBlob();
        long limit = wallClockMillis.getAsLong() + MAX_FUTURE_SKEW_MS;
        if (blob.getBlobTimestamp() > limit) {
            return "timestamp " + blob.getBlobTimestamp() + " is in the future (now+60s = " + limit + ")";
        }
        ECPublicKey received;
        try {
            received = Curve.decodePoint(response.getServerPublicKey().toByteArray(), 0);
        } catch (InvalidKeyException | RuntimeException e) {
            return "unreadable server public key";
        }
        if (!serverIdentityKey.equals(received)) return "signed by unknown server";
        try {
            if (!SecurityUtils.verifySignatureRaw(blob.toByteArray(), received,
                                                  response.getRecencyBlobSignature().toByteArray())) {
                return "signature verification failed";
            }
        } catch (InvalidKeyException | RuntimeException e) {
            return "signature verification failed: " + e.getMessage();
        }
        return null;
    }

    @Override
    public String toString() {
        return "ServerTrust{" + Base64.getUrlEncoder().encodeToString(serverIdentityKey.serialize()) + "}";
    }
}
