package net.discdd.bundledropbox.store;

import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.utils.BundleUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.InvalidParameterException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Bundle-id helpers: reads the encrypted bundle id out of a bundle jar and validates ids before they
 * become file names.
 *
 * <p>Why the dropbox needs this: in VISIT mode it pulls every client-bound bundle from a carrier with
 * {@code psiDownloadBundle(session, index)}, whose response carries only chunks, no name. Every bundle
 * is a jar built by {@code BundleUtils.encryptPayloadAndCreateBundle}, which writes the encrypted id
 * as entry {@code bundle.id}, and that id equals the carrier's file name and the string the stock
 * client blinds in PSI (Research/R2 §4.2). So the id read here is what the bundle must be stored as,
 * or the client will never PSI-match it (Research/R3 §7).
 *
 * <p>It also extracts the two pieces of plaintext jar metadata the store records per bundle
 * ({@link #inspect}): the client tag from {@code clientBase.pub} and the jar build time. Both are visible to
 * every carrier already, so reading them adds no exposure (Research/R8 §5).
 *
 * <p>Pure Java ({@code java.util.zip}), no Android imports. Stateless and thread-safe.
 */
public final class BundleIds {
    /**
     * Jar entry holding the encrypted bundle id. Uses the bundle-core constant
     * ({@code SecurityUtils.BUNDLEID_FILENAME = "bundle.id"}) so the dropbox follows any rename.
     */
    public static final String BUNDLE_ID_ENTRY = SecurityUtils.BUNDLEID_FILENAME;

    /**
     * Plaintext jar entry holding the client's base public key ({@code SecurityUtils.CLIENT_BASE_KEY =
     * "clientBase.pub"}). {@code BundleUtils.encryptPayloadAndCreateBundle} writes it unencrypted in every
     * bundle, upstream and downstream, and the server reuses the client's key for that client's downstream
     * bundles, so it is a stable per-client tag (Research/R8 §5).
     */
    public static final String CLIENT_BASE_KEY_ENTRY = SecurityUtils.CLIENT_BASE_KEY;

    /**
     * Upper bound on the bytes read from the {@code bundle.id} entry. Valid ids are at most 100 chars
     * ({@code BundleUtils.checkIdClean}); the cap stops a hostile jar from making us read a huge entry.
     */
    private static final int MAX_ID_ENTRY_BYTES = 4096;

    private BundleIds() {}

    /**
     * Reads and validates the bundle id stored in a bundle jar.
     *
     * @param jar a bundle file
     * @return the clean id
     * @throws IOException if the file is not a readable zip, has no non-empty {@code bundle.id} entry, or
     *                     the id fails {@link #requireClean(String)}
     */
    public static String read(Path jar) throws IOException {
        String raw = readRaw(jar);
        if (raw == null) {
            throw new IOException("No " + BUNDLE_ID_ENTRY + " entry in " + jar.getFileName());
        }
        return requireClean(raw);
    }

    /**
     * Like {@link #read(Path)} but reports every failure as an empty result and does not validate. The store
     * uses {@link #inspect} instead, which reads the same entry plus the per-client metadata in one pass.
     *
     * @param jar a file that may or may not be a bundle jar
     * @return the id from {@code bundle.id} (trimmed, not yet validated), or empty if the file is not a
     *         zip, the entry is missing, empty or oversized
     */
    public static Optional<String> tryReadRaw(Path jar) {
        try {
            return Optional.ofNullable(readRaw(jar));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Reads everything the store records about a bundle jar in one pass over its central directory.
     *
     * @param jar a file that may or may not be a bundle jar
     * @return the jar's metadata, or empty if the file is not a readable zip. Individual fields may still be
     *         missing (see {@link JarInfo})
     */
    public static Optional<JarInfo> inspect(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            String rawId = null;
            long builtAt = BundleRecord.UNKNOWN_TIME;
            ZipEntry idEntry = zip.getEntry(BUNDLE_ID_ENTRY);
            if (idEntry != null && !idEntry.isDirectory()) {
                rawId = readEntry(zip, idEntry);
                // JarOutputStream stamps every entry with the time it was written (DDDJarFileCreator sets no
                // explicit time), so this is when the server (DOWNSTREAM) or client (UPSTREAM) built the jar.
                // DOS time: 2 s resolution, interpreted in this device's time zone; only ever compared between
                // jars built by the same server, so a constant zone offset cancels out.
                long t = idEntry.getTime();
                if (t > 0) builtAt = t;
            }
            String tag = null;
            ZipEntry keyEntry = zip.getEntry(CLIENT_BASE_KEY_ENTRY);
            if (keyEntry != null && !keyEntry.isDirectory()) {
                String pem = readEntry(zip, keyEntry);
                if (pem != null) tag = clientTag(pem);
            }
            return Optional.of(new JarInfo(rawId, tag, builtAt));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Computes the per-client tag R8 §5 proposes: {@code base64url(SHA-256(key bytes))}, where the key
     * bytes are the Base64url payload line of the PEM-style {@code clientBase.pub} entry
     * ({@code DDDPEMEncoder.createEncodedPublicKeyBytes}). If the entry is not in that 3-line form, the
     * trimmed entry text itself is hashed: still stable per client, just not comparable with the server's tag.
     *
     * @param pem trimmed content of the {@code clientBase.pub} entry
     * @return the tag (43 chars, no padding)
     */
    static String clientTag(String pem) {
        byte[] material = pem.getBytes(StandardCharsets.UTF_8);
        String[] lines = pem.split("\\r?\\n");
        if (lines.length == 3 && lines[0].startsWith("-----BEGIN") && lines[2].startsWith("-----END")) {
            try {
                material = Base64.getUrlDecoder().decode(lines[1].trim());
            } catch (IllegalArgumentException e) {
                // keep hashing the raw text
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Validates an id with bundle-core's {@code BundleUtils.checkIdClean} (regex
     * {@code ^[a-zA-Z0-9-_=]+$}, max 100 chars), the same check the transport applies on download.
     *
     * <p>The stock transport never validates upload ids (Research/R2 §3, R4 §4.1), so a peer could claim
     * anything; the id becomes a file name here, so it is always checked first. Note the regex's
     * {@code 9-_} is a character range (it also admits {@code :;<>?@[\]^}); it still excludes
     * {@code /} and {@code .}, which is what matters for path safety. {@link FileRelayStore} additionally
     * verifies the resolved path stays inside its directory.
     *
     * @param id candidate id
     * @return {@code id} unchanged
     * @throws IOException if the id is null or unclean
     */
    public static String requireClean(String id) throws IOException {
        if (id == null) throw new IOException("Bundle id is null");
        try {
            BundleUtils.checkIdClean(id);
        } catch (InvalidParameterException e) {
            throw new IOException("Unclean bundle id: " + abbreviate(id), e);
        }
        return id;
    }

    /**
     * @param id candidate id (may be null)
     * @return true if {@link #requireClean(String)} would accept it
     */
    public static boolean isClean(String id) {
        try {
            requireClean(id);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * @return the trimmed {@code bundle.id} content, or null if the entry is missing, empty or oversized
     * @throws IOException if the file is not a readable zip
     */
    private static String readRaw(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(BUNDLE_ID_ENTRY);
            if (entry == null || entry.isDirectory()) return null;
            return readEntry(zip, entry);
        }
    }

    /**
     * @return the trimmed entry text, or null if it is empty or larger than {@link #MAX_ID_ENTRY_BYTES} (the
     *         cap stops a hostile jar from making us read a huge entry; ids and PEM keys are far smaller)
     */
    private static String readEntry(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(128);
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > MAX_ID_ENTRY_BYTES) return null;
            }
            // trim() matches BundleUtils.extractBundle, which also trims the entry it reads.
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? null : text;
        }
    }

    /** Plaintext metadata of one bundle jar ({@link #inspect}). Immutable. */
    public static final class JarInfo {
        private final String rawId;
        private final String clientTag;
        private final long builtAtMillis;

        JarInfo(String rawId, String clientTag, long builtAtMillis) {
            this.rawId = rawId;
            this.clientTag = clientTag;
            this.builtAtMillis = builtAtMillis;
        }

        /** @return the trimmed, NOT yet validated {@code bundle.id} content, or null if missing/empty/oversized */
        public String rawId() { return rawId; }

        /** @return the client tag from {@code clientBase.pub}, or null if the entry is missing */
        public String clientTag() { return clientTag; }

        /** @return the {@code bundle.id} entry's timestamp, or {@link BundleRecord#UNKNOWN_TIME} */
        public long builtAtMillis() { return builtAtMillis; }
    }

    private static String abbreviate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...(" + s.length() + " chars)";
    }
}
