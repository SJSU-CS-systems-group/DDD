package net.discdd.dropbox.control;

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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the plaintext metadata of a bundle jar without any keys, and computes the client key fingerprint that
 * the server and the dropbox both use to tag bundles by client ({@code clientKeyB64} in {@link InventoryReport}).
 *
 * <p>What is plaintext in a bundle jar ({@code BundleUtils.encryptPayloadAndCreateBundle}, Research/R7 §3,
 * R8 §5):
 * <ul>
 *   <li>{@code bundle.id}: the encrypted bundle ID, which is also the bundle's file name everywhere.</li>
 *   <li>{@code clientBase.pub}: the client's base public key as a 3-line PEM-style text
 *       ({@code DDDPEMEncoder.createEncodedPublicKeyBytes}). It is stable per client, and the server writes the
 *       same key into that client's downstream bundles, so it tags bundles in both directions.</li>
 *   <li>{@code server_identity.pub}: the server's key (not read here).</li>
 * </ul>
 * {@code clientIdentity.pub} is <em>not</em> usable: it is encrypted to the server with a fresh ephemeral key per
 * bundle ({@code DDDPEMEncoder.createEncryptedEncodedPublicKeyBytes}), so it differs between bundles of the same
 * client and only the server can read it. The payload is Signal ciphertext.
 *
 * <p>Every carrier already sees {@code clientBase.pub}, so the fingerprint adds no exposure (Research/R8 §5).
 * The fingerprint is {@code base64url-nopad(SHA-256(raw key bytes))}, where the raw key bytes are the decoded
 * Base64url middle line of the PEM, i.e. {@code ECPublicKey.serialize()}. The server computes the same value
 * from its stored {@code clientBase.pub} ({@code DDDPEMEncoder.decodePublicKeyfromFile}) or from
 * {@code ServerSecurity.getClientBaseKey(clientId).serialize()}. It matches the dropbox's existing
 * {@code BundleIds.clientTag} for well-formed keys.
 *
 * <p>Immutable result; the static methods are stateless and thread-safe. Pure Java ({@code java.util.zip}).
 */
public final class BundleJarInfo {
    /** Entry reads are capped: IDs are at most 100 characters and PEM keys about 100 bytes, so 4 KiB is ample
     *  and stops a hostile jar from making us read a huge entry. */
    static final int MAX_ENTRY_BYTES = 4096;

    /** Returned by {@link #getBundleIdEntryTimeMillis()} when the jar does not carry a usable time. */
    public static final long UNKNOWN_TIME = -1L;

    private final String bundleId;
    private final String clientKeyFingerprint;
    private final long bundleIdEntryTimeMillis;

    private BundleJarInfo(String bundleId, String clientKeyFingerprint, long bundleIdEntryTimeMillis) {
        this.bundleId = bundleId;
        this.clientKeyFingerprint = clientKeyFingerprint;
        this.bundleIdEntryTimeMillis = bundleIdEntryTimeMillis;
    }

    /**
     * Reads a bundle jar's plaintext metadata.
     *
     * @param jar a bundle file
     * @return the info; the bundle ID is always present and clean, the fingerprint may be null
     * @throws IOException if the file is not a readable zip, or its {@code bundle.id} entry is missing, empty,
     *                     oversized or not a clean ID ({@code BundleUtils.checkIdClean})
     */
    public static BundleJarInfo read(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry idEntry = zip.getEntry(SecurityUtils.BUNDLEID_FILENAME);
            String id = idEntry == null || idEntry.isDirectory() ? null : readEntry(zip, idEntry);
            if (id == null) {
                throw new IOException("No usable " + SecurityUtils.BUNDLEID_FILENAME + " entry in " + jar.getFileName());
            }
            try {
                BundleUtils.checkIdClean(id);
            } catch (InvalidParameterException e) {
                throw new IOException("Unclean bundle id in " + jar.getFileName(), e);
            }
            // JarOutputStream stamps each entry with the time it was written (DDDJarFileCreator sets no explicit
            // time), so this approximates when the jar was built. DOS time: 2 s resolution, local time zone.
            long t = idEntry.getTime();

            String fingerprint = null;
            ZipEntry keyEntry = zip.getEntry(SecurityUtils.CLIENT_BASE_KEY);
            if (keyEntry != null && !keyEntry.isDirectory()) {
                String pem = readEntry(zip, keyEntry);
                if (pem != null) fingerprint = clientKeyFingerprintFromPem(pem);
            }
            return new BundleJarInfo(id, fingerprint, t > 0 ? t : UNKNOWN_TIME);
        }
    }

    /**
     * @param rawKeyBytes the serialized client base public key ({@code ECPublicKey.serialize()})
     * @return {@code base64url-nopad(SHA-256(rawKeyBytes))}, 43 characters
     */
    public static String clientKeyFingerprint(byte[] rawKeyBytes) {
        if (rawKeyBytes == null) throw new IllegalArgumentException("rawKeyBytes is null");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawKeyBytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Fingerprints the text of a {@code clientBase.pub} entry or file.
     *
     * @param pem the PEM-style text: header line, Base64url key line, footer line (surrounding whitespace and
     *            CRLF line ends are tolerated)
     * @return the fingerprint, or null if the text is not in that 3-line form or the key line is not Base64url
     */
    public static String clientKeyFingerprintFromPem(String pem) {
        if (pem == null) return null;
        String[] lines = pem.trim().split("\\r?\\n");
        if (lines.length != 3 || !lines[0].trim().startsWith("-----BEGIN") ||
                !lines[2].trim().startsWith("-----END")) {
            return null;
        }
        try {
            byte[] raw = Base64.getUrlDecoder().decode(lines[1].trim());
            return raw.length == 0 ? null : clientKeyFingerprint(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** @return the clean, trimmed encrypted bundle ID from {@code bundle.id} */
    public String getBundleId() {return bundleId;}

    /** @return the {@code clientBase.pub} fingerprint, or null if the entry is missing or malformed */
    public String getClientKeyFingerprint() {return clientKeyFingerprint;}

    /** @return the {@code bundle.id} entry's timestamp in epoch ms, or {@link #UNKNOWN_TIME} */
    public long getBundleIdEntryTimeMillis() {return bundleIdEntryTimeMillis;}

    @Override
    public String toString() {
        return "BundleJarInfo{" + bundleId + ", clientKey=" + clientKeyFingerprint + "}";
    }

    /**
     * @return the trimmed entry text, or null if it is empty or larger than {@link #MAX_ENTRY_BYTES}. Trimming
     *         matches {@code BundleUtils.extractBundle}, which also trims {@code bundle.id}.
     */
    private static String readEntry(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(128);
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > MAX_ENTRY_BYTES) return null;
            }
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? null : text;
        }
    }
}
