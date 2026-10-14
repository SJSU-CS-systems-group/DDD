package net.discdd.bundledropbox.service;

import net.discdd.bundledropbox.store.DropboxPaths;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.dropbox.control.RecencyRequestSigner;
import net.discdd.tls.GrpcSecurityKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Base64;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The dropbox's identity: its TLS key pair, the stable {@code baseId} derived from it, which it advertises in the
 * DNS-SD TXT {@code transportId} field, and the sender id the server mints its recency blobs for.
 *
 * <ul>
 *   <li><b>Keys.</b> {@code new GrpcSecurityKey(paths.grpcSecurity, SecurityUtils.SERVER)}, exactly like
 *       the transport (Research/R1 §2.4): created on first run, loaded afterwards. The same key serves
 *       clients over TLS (HOST), is presented as the client certificate toward carriers (VISIT), and signs the
 *       dropbox's recency requests.</li>
 *   <li><b>baseId.</b> Derived the way {@code BundleTransportService} derives {@code transportId}:
 *       Android {@code Base64.DEFAULT} of the encoded public key, then {@code substring(len-20, len-4)}.
 *       {@code Base64.DEFAULT} wraps at 76 chars and ends with a newline, which is why the transport's
 *       offsets skip the last 4 chars; we keep the identical formula so a dropbox id looks like a transport
 *       id. It names the Wi-Fi Direct group ({@code DIRECT-ddd_<baseId>}) and is the advertised
 *       {@code transportId}; it never changes.</li>
 *   <li><b>advertisedId = baseId.</b> A stock client only exchanges with a transport when the transport's
 *       {@code blobTimestamp} is newer than the client's last exchange with that {@code transportId}
 *       (Research/R3). The dropbox gets a fresh server-issued blob of its own on every control round trip, like a
 *       transport on every server sync, so a stable id works the way it does for a transport (09 Own Recency
 *       Blob Design). The per-hosting-period id rotation of earlier builds is gone; the generation file it left
 *       in {@code meta/} is deleted at load.</li>
 *   <li><b>ownSenderId.</b> {@code SecurityUtils.generateID} of the X.509 DER of the TLS public key
 *       ({@link RecencyRequestSigner#senderIdFor}), the value the server puts in a blob minted for this key and
 *       the one its gRPC {@code getRecencyBlob} computes for a peer presenting this certificate. The recency
 *       store keeps only blobs carrying it.</li>
 * </ul>
 *
 * <p>Immutable and thread-safe.
 */
public final class DropboxIdentity {
    private static final Logger logger = Logger.getLogger(DropboxIdentity.class.getName());

    /** File under {@code meta/} where builds with advertised-id rotation kept their generation; deleted at load. */
    static final String LEGACY_GENERATION_FILE_NAME = "advertisedIdGeneration";

    private final GrpcSecurityKey keys;
    private final String baseId;
    private final String ownSenderId;

    /**
     * @param keys          the dropbox TLS identity
     * @param base64Encoder encodes the public key exactly like Android {@code Base64.DEFAULT} (the service
     *                      passes {@code b -> android.util.Base64.encodeToString(b, Base64.DEFAULT)}; JVM
     *                      tests pass an equivalent pure-Java encoder)
     */
    public DropboxIdentity(GrpcSecurityKey keys, Function<byte[], String> base64Encoder) {
        this.keys = keys;
        byte[] publicDer = keys.grpcKeyPair.getPublic().getEncoded();
        this.baseId = deriveBaseId(base64Encoder.apply(publicDer));
        this.ownSenderId = RecencyRequestSigner.senderIdFor(Base64.getEncoder().encodeToString(publicDer));
    }

    /**
     * Loads (or creates on first run) the dropbox keys under {@code paths.grpcSecurity}.
     *
     * @param paths         dropbox paths ({@code meta/} must exist: call {@code createDirectories()} first)
     * @param base64Encoder Android {@code Base64.DEFAULT}-compatible encoder
     * @return the identity
     * @throws Exception if the key material cannot be created or read (fatal for the dropbox)
     */
    public static DropboxIdentity load(DropboxPaths paths, Function<byte[], String> base64Encoder) throws Exception {
        GrpcSecurityKey keys = new GrpcSecurityKey(paths.grpcSecurity, SecurityUtils.SERVER);
        deleteLegacyGenerationFile(paths.meta.resolve(LEGACY_GENERATION_FILE_NAME));
        return new DropboxIdentity(keys, base64Encoder);
    }

    /**
     * The transport's id formula ({@code BundleTransportService}: {@code substring(len - 20, len - 4)} of
     * the {@code Base64.DEFAULT} encoding).
     *
     * @param fullBase64 Android {@code Base64.DEFAULT} encoding of the public key (with line breaks)
     * @return the 16-char base id
     */
    public static String deriveBaseId(String fullBase64) {
        if (fullBase64 == null || fullBase64.length() < 20) {
            throw new IllegalArgumentException("encoded public key too short for an id");
        }
        return fullBase64.substring(fullBase64.length() - 20, fullBase64.length() - 4);
    }

    /** @return the TLS identity (HOST server key and VISIT client certificate). */
    public GrpcSecurityKey keys() {
        return keys;
    }

    /** @return the TLS EC key pair that signs the dropbox's recency requests. */
    public KeyPair tlsKeyPair() {
        return keys.grpcKeyPair;
    }

    /** @return the stable 16-char id used in the group name {@code DIRECT-ddd_<baseId>}. */
    public String baseId() {
        return baseId;
    }

    /** @return the TXT {@code transportId}: always {@link #baseId()}. */
    public String advertisedId() {
        return baseId;
    }

    /** @return the sender id the server mints this dropbox's recency blobs for. */
    public String ownSenderId() {
        return ownSenderId;
    }

    /** Removes the counter file of the retired advertised-id rotation; a failure only leaves an unused file. */
    private static void deleteLegacyGenerationFile(Path file) {
        try {
            if (Files.deleteIfExists(file)) logger.log(Level.INFO, "Deleted unused " + file.getFileName());
        } catch (IOException e) {
            logger.log(Level.FINE, "Could not delete unused " + file, e);
        }
    }
}
