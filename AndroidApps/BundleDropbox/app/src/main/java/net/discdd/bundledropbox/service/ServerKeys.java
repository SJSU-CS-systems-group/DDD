package net.discdd.bundledropbox.service;

import net.discdd.bundlesecurity.DDDPEMEncoder;
import net.discdd.bundlesecurity.SecurityUtils;

import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECPublicKey;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * The DDD server's public keys the dropbox trusts, from one of two sources:
 * <ul>
 *   <li>{@link Source#BUNDLED}: android-core's {@code R.raw.server_identity}, {@code server_signed_pre} and
 *       {@code server_ratchet}, the same defaults the stock BundleClient uses when it has no custom keys
 *       ({@code BundleClientService}, "custom_server_keys" false).</li>
 *   <li>{@link Source#IMPORTED}: keys saved by {@link #saveImported}, preferred when all three files exist.
 *       Written by the Settings "Server keys (QR)" import ({@link ServerKeysUrl}), which reads BundleClient's
 *       {@code https://discdd.net/srvr?...identity=..&signedpre=..&ratchet=..} URL so the dropbox can trust a
 *       laptop server (06 Storage Management Design, "Server keys for the endpoint").</li>
 * </ul>
 *
 * <p>Uses: the identity key pins recency blobs (audit M5/M6: a stock client rejects a blob whose
 * {@code serverPublicKey} is not its server's, so the dropbox must not store or serve one either); the three
 * PEM files are what {@code ClientPaths} needs for the control endpoint ({@code control/DropboxControlEndpoint}).
 *
 * <p>Pure Java; the service passes the raw-resource streams in. Immutable and thread-safe.
 */
public final class ServerKeys {
    /** Directory (under the dropbox's {@code meta/}) holding imported keys. */
    public static final String IMPORTED_DIR_NAME = "serverKeys";

    /** File in the imported-keys directory naming where the keys came from (e.g. {@code host:port}). */
    static final String ORIGIN_FILE_NAME = "origin.txt";

    /** Where the keys came from. */
    public enum Source { BUNDLED, IMPORTED }

    /** Opens one of the bundled key files ({@code Resources.openRawResource} in production). */
    @FunctionalInterface
    public interface KeyStream {
        InputStream open() throws IOException;
    }

    private final Source source;
    private final byte[] identityPem;
    private final byte[] signedPrePem;
    private final byte[] ratchetPem;
    private final ECPublicKey identityKey;
    private final String origin;

    private ServerKeys(Source source, byte[] identityPem, byte[] signedPrePem, byte[] ratchetPem)
            throws InvalidKeyException {
        this(source, identityPem, signedPrePem, ratchetPem, null);
    }

    private ServerKeys(Source source, byte[] identityPem, byte[] signedPrePem, byte[] ratchetPem, String origin)
            throws InvalidKeyException {
        this.source = source;
        this.origin = origin == null || origin.isBlank() ? null : origin.trim();
        this.identityPem = identityPem;
        this.signedPrePem = signedPrePem;
        this.ratchetPem = ratchetPem;
        this.identityKey = parsePublicKeyPem(identityPem);
        parsePublicKeyPem(signedPrePem); // validate now rather than when the control endpoint first needs them
        parsePublicKeyPem(ratchetPem);
    }

    /**
     * Loads the imported keys if all three are present and valid, else the bundled defaults.
     *
     * @param importedDir {@code meta/serverKeys}
     * @param identity    bundled {@code server_identity.pub}
     * @param signedPre   bundled {@code server_signed_pre.pub}
     * @param ratchet     bundled {@code server_ratchet.pub}
     * @throws IOException         if the bundled keys cannot be read
     * @throws InvalidKeyException if the bundled keys are malformed
     */
    public static ServerKeys load(Path importedDir, KeyStream identity, KeyStream signedPre, KeyStream ratchet)
            throws IOException, InvalidKeyException {
        ServerKeys imported = loadImported(importedDir);
        if (imported != null) return imported;
        return new ServerKeys(Source.BUNDLED, read(identity), read(signedPre), read(ratchet));
    }

    /**
     * Saves keys imported from a server QR code (each the base64url key text from the URL, without PEM
     * armour) so the next {@link #load} prefers them. Writes atomically; validates first.
     *
     * @return the imported keys
     * @throws InvalidKeyException if any key does not decode
     */
    public static ServerKeys saveImported(Path importedDir, String identityB64, String signedPreB64,
                                          String ratchetB64) throws IOException, InvalidKeyException {
        return saveImported(importedDir, identityB64, signedPreB64, ratchetB64, null);
    }

    /**
     * {@link #saveImported(Path, String, String, String)} that also records where the keys came from.
     *
     * @param origin operator-facing origin such as {@code host:port} from the QR URL, or {@code null}
     */
    public static ServerKeys saveImported(Path importedDir, String identityB64, String signedPreB64,
                                          String ratchetB64, String origin) throws IOException, InvalidKeyException {
        ServerKeys keys = new ServerKeys(Source.IMPORTED, pem(identityB64), pem(signedPreB64), pem(ratchetB64),
                                         origin);
        Files.createDirectories(importedDir);
        writeAtomically(importedDir.resolve(SecurityUtils.SERVER_IDENTITY_KEY), keys.identityPem);
        writeAtomically(importedDir.resolve(SecurityUtils.SERVER_SIGNED_PRE_KEY), keys.signedPrePem);
        writeAtomically(importedDir.resolve(SecurityUtils.SERVER_RATCHET_KEY), keys.ratchetPem);
        Path originFile = importedDir.resolve(ORIGIN_FILE_NAME);
        if (keys.origin == null) Files.deleteIfExists(originFile);
        else writeAtomically(originFile, keys.origin.getBytes(StandardCharsets.UTF_8));
        return keys;
    }

    /**
     * Forgets imported keys, so the next {@link #load} returns the bundled defaults.
     *
     * @param importedDir {@code meta/serverKeys}
     */
    public static void deleteImported(Path importedDir) throws IOException {
        for (String name : new String[]{ SecurityUtils.SERVER_IDENTITY_KEY, SecurityUtils.SERVER_SIGNED_PRE_KEY,
                                         SecurityUtils.SERVER_RATCHET_KEY, ORIGIN_FILE_NAME }) {
            Files.deleteIfExists(importedDir.resolve(name));
        }
    }

    /** @return where the keys came from */
    public Source source() {
        return source;
    }

    /** @return the recorded origin of imported keys (e.g. {@code host:port}), or {@code null} */
    public String origin() {
        return origin;
    }

    /** @return operator-facing description: "built-in", "imported" or "imported from host:port" */
    public String describe() {
        if (source == Source.BUNDLED) return "built-in";
        return origin == null ? "imported" : "imported from " + origin;
    }

    /** @return the server identity key: the key recency blobs must be signed with */
    public ECPublicKey identityKey() {
        return identityKey;
    }

    /** @return {@link #identityKey()} serialized as in {@code GetRecencyBlobResponse.serverPublicKey} */
    public byte[] identityKeyBytes() {
        return identityKey.serialize();
    }

    /**
     * @param serverPublicKey a {@code GetRecencyBlobResponse.serverPublicKey} value
     * @return true if it is this server's identity key (what the stock client checks, R3)
     */
    public boolean isTrustedServerKey(byte[] serverPublicKey) {
        if (serverPublicKey == null) return false;
        try {
            return identityKey.equals(Curve.decodePoint(serverPublicKey, 0));
        } catch (InvalidKeyException | RuntimeException e) {
            return false;
        }
    }

    /** @return PEM text of {@code server_identity.pub} (for {@code ClientPaths}) */
    public byte[] identityPem() {
        return identityPem.clone();
    }

    /** @return PEM text of {@code server_signed_pre.pub} */
    public byte[] signedPrePem() {
        return signedPrePem.clone();
    }

    /** @return PEM text of {@code server_ratchet.pub} */
    public byte[] ratchetPem() {
        return ratchetPem.clone();
    }

    /**
     * Decodes a DDD public-key file: {@code -----BEGIN EC PUBLIC KEY-----}, one base64url line of the serialized
     * curve point, {@code -----END EC PUBLIC KEY-----} (the format of {@code DDDPEMEncoder}).
     */
    public static ECPublicKey parsePublicKeyPem(byte[] pem) throws InvalidKeyException {
        String[] lines = new String(pem, StandardCharsets.UTF_8).trim().split("\\R");
        if (lines.length != 3 || !lines[0].trim().equals(DDDPEMEncoder.PUB_KEY_HEADER) ||
                !lines[2].trim().equals(DDDPEMEncoder.PUB_KEY_FOOTER)) {
            throw new InvalidKeyException("not an EC PUBLIC KEY file");
        }
        try {
            return Curve.decodePoint(Base64.getUrlDecoder().decode(lines[1].trim()), 0);
        } catch (IllegalArgumentException e) {
            throw new InvalidKeyException("bad base64 in public key: " + e.getMessage());
        }
    }

    private static ServerKeys loadImported(Path dir) {
        Path id = dir.resolve(SecurityUtils.SERVER_IDENTITY_KEY);
        Path pre = dir.resolve(SecurityUtils.SERVER_SIGNED_PRE_KEY);
        Path ratchet = dir.resolve(SecurityUtils.SERVER_RATCHET_KEY);
        if (!Files.isRegularFile(id) || !Files.isRegularFile(pre) || !Files.isRegularFile(ratchet)) return null;
        try {
            Path originFile = dir.resolve(ORIGIN_FILE_NAME);
            String origin = Files.isRegularFile(originFile) ?
                            new String(Files.readAllBytes(originFile), StandardCharsets.UTF_8) : null;
            return new ServerKeys(Source.IMPORTED, Files.readAllBytes(id), Files.readAllBytes(pre),
                                  Files.readAllBytes(ratchet), origin);
        } catch (IOException | InvalidKeyException e) {
            // A damaged import must not stop the relay: fall back to the bundled keys (logged by the caller).
            return null;
        }
    }

    private static byte[] pem(String base64Url) throws InvalidKeyException {
        if (base64Url == null || base64Url.isBlank()) throw new InvalidKeyException("missing key");
        return (DDDPEMEncoder.PUB_KEY_HEADER + "\n" + base64Url.trim() + "\n" + DDDPEMEncoder.PUB_KEY_FOOTER)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] read(KeyStream s) throws IOException {
        try (InputStream in = Objects.requireNonNull(s.open(), "key stream")) {
            return in.readAllBytes();
        }
    }

    private static void writeAtomically(Path file, byte[] bytes) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public String toString() {
        return "ServerKeys{" + source + ", identity=" + Arrays.toString(Arrays.copyOf(identityKeyBytes(), 4)) + "...}";
    }
}
