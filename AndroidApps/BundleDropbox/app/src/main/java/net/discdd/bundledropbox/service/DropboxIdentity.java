package net.discdd.bundledropbox.service;

import net.discdd.bundledropbox.store.DropboxPaths;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.tls.GrpcSecurityKey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The dropbox's identity: its TLS key pair, the stable {@code baseId} derived from it, and the rotating
 * {@code advertisedId} it puts in the DNS-SD TXT {@code transportId} field.
 *
 * <ul>
 *   <li><b>Keys.</b> {@code new GrpcSecurityKey(paths.grpcSecurity, SecurityUtils.SERVER)}, exactly like
 *       the transport (Research/R1 §2.4): created on first run, loaded afterwards. The same key serves
 *       clients over TLS (HOST) and is presented as the client certificate toward carriers (VISIT).</li>
 *   <li><b>baseId.</b> Derived the way {@code BundleTransportService} derives {@code transportId}:
 *       Android {@code Base64.DEFAULT} of the encoded public key, then {@code substring(len-20, len-4)}.
 *       {@code Base64.DEFAULT} wraps at 76 chars and ends with a newline, which is why the transport's
 *       offsets skip the last 4 chars; we keep the identical formula so a dropbox id looks like a transport
 *       id. It names the Wi-Fi Direct group ({@code DIRECT-ddd_<baseId>}) and never changes.</li>
 *   <li><b>advertisedId.</b> A stock client only exchanges with a transport when the transport's
 *       {@code blobTimestamp} is newer than the client's last exchange with that {@code transportId}
 *       (Research/R3). The dropbox re-serves a carrier's blob, so after one exchange a client would ignore
 *       it until the carrier's server sync advanced. 03 Architecture §5: after each visit where the recency
 *       fetch succeeded the dropbox bumps a persisted generation and advertises {@code baseId-gen}, so
 *       clients see a "new" transport. With rotation off it advertises plain {@code baseId}.</li>
 * </ul>
 *
 * <p>The generation lives in {@code meta/advertisedIdGeneration} (decimal text, atomic replace), so it
 * survives restarts and an id is never reused for a different blob.
 *
 * <p>Thread-safe: generation reads/writes are synchronized; {@code rotate} is volatile because the
 * service flips it from its SharedPreferences listener while the Wi-Fi host reads the id.
 */
public final class DropboxIdentity {
    private static final Logger logger = Logger.getLogger(DropboxIdentity.class.getName());

    /** File under {@code meta/} holding the advertised-id generation counter. */
    public static final String GENERATION_FILE_NAME = "advertisedIdGeneration";

    private final GrpcSecurityKey keys;
    private final String baseId;
    private final Path generationFile;
    private volatile boolean rotate;
    private long generation; // guarded by this

    /**
     * @param keys           the dropbox TLS identity
     * @param base64Encoder  encodes the public key exactly like Android {@code Base64.DEFAULT} (the service
     *                       passes {@code b -> android.util.Base64.encodeToString(b, Base64.DEFAULT)}; JVM
     *                       tests pass an equivalent pure-Java encoder)
     * @param generationFile where the generation counter is persisted
     * @param rotate         whether {@link #advertisedId()} carries the generation suffix
     */
    public DropboxIdentity(GrpcSecurityKey keys, Function<byte[], String> base64Encoder, Path generationFile,
                           boolean rotate) {
        this.keys = keys;
        this.baseId = deriveBaseId(base64Encoder.apply(keys.grpcKeyPair.getPublic().getEncoded()));
        this.generationFile = generationFile;
        this.rotate = rotate;
        this.generation = loadGeneration(generationFile);
    }

    /**
     * Loads (or creates on first run) the dropbox keys under {@code paths.grpcSecurity}.
     *
     * @param paths         dropbox paths ({@code meta/} must exist: call {@code createDirectories()} first)
     * @param base64Encoder Android {@code Base64.DEFAULT}-compatible encoder
     * @param rotate        initial value of the {@code rotate_advertised_id} setting
     * @return the identity
     * @throws Exception if the key material cannot be created or read (fatal for the dropbox)
     */
    public static DropboxIdentity load(DropboxPaths paths, Function<byte[], String> base64Encoder, boolean rotate)
            throws Exception {
        GrpcSecurityKey keys = new GrpcSecurityKey(paths.grpcSecurity, SecurityUtils.SERVER);
        return new DropboxIdentity(keys, base64Encoder, paths.meta.resolve(GENERATION_FILE_NAME), rotate);
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

    /** @return the stable 16-char id used in the group name {@code DIRECT-ddd_<baseId>}. */
    public String baseId() {
        return baseId;
    }

    /** @return the TXT {@code transportId}: {@code baseId-gen} with rotation on, else {@code baseId}. */
    public synchronized String advertisedId() {
        return rotate ? baseId + "-" + generation : baseId;
    }

    /** @return current generation (0 on a fresh install). */
    public synchronized long generation() {
        return generation;
    }

    /** @return whether the advertised id rotates. */
    public boolean isRotating() {
        return rotate;
    }

    /** Applies the {@code rotate_advertised_id} setting; takes effect at the next advertisement. */
    public void setRotate(boolean rotate) {
        this.rotate = rotate;
    }

    /**
     * Advances the generation and persists it. Called by the service after a visit whose recency fetch
     * succeeded, when rotation is on. The new id is advertised at the next {@code startHosting}.
     *
     * @return the new advertised id
     */
    public synchronized String bumpGeneration() {
        generation++;
        try {
            writeAtomically(generationFile, Long.toString(generation));
        } catch (IOException e) {
            // Keep the in-memory value: the worst case after a crash is re-advertising an older id,
            // which only makes clients skip one exchange until the next bump.
            logger.log(Level.WARNING, "Could not persist advertised-id generation " + generation, e);
        }
        return advertisedId();
    }

    private static long loadGeneration(Path file) {
        try {
            if (!Files.exists(file)) return 0;
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            long g = Long.parseLong(text);
            return Math.max(0, g);
        } catch (IOException | NumberFormatException e) {
            logger.log(Level.WARNING, "Unreadable " + file + "; restarting generation at 0", e);
            return 0;
        }
    }

    /** Write to a temp file in the same directory, then atomically replace (crash leaves old or new). */
    private static void writeAtomically(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
