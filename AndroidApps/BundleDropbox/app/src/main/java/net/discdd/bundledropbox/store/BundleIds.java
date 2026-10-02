package net.discdd.bundledropbox.store;

import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.utils.BundleUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.InvalidParameterException;
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
 * <p>Pure Java ({@code java.util.zip}), no Android imports. Stateless and thread-safe.
 */
public final class BundleIds {
    /**
     * Jar entry holding the encrypted bundle id. Uses the bundle-core constant
     * ({@code SecurityUtils.BUNDLEID_FILENAME = "bundle.id"}) so the dropbox follows any rename.
     */
    public static final String BUNDLE_ID_ENTRY = SecurityUtils.BUNDLEID_FILENAME;

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
     * Like {@link #read(Path)} but reports every failure as an empty result. Used by
     * {@link FileRelayStore#commit} where a missing jar id is acceptable if the peer claimed one.
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
            try (InputStream in = zip.getInputStream(entry)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(128);
                byte[] buf = new byte[512];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_ID_ENTRY_BYTES) return null;
                }
                // trim() matches BundleUtils.extractBundle, which also trims the entry it reads.
                String id = new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
                return id.isEmpty() ? null : id;
            }
        }
    }

    private static String abbreviate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...(" + s.length() + " chars)";
    }
}
