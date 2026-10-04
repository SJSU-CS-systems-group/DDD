package net.discdd.dropbox.control;

import net.discdd.bundlesecurity.DDDPEMEncoder;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.utils.BundleUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECPublicKey;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests {@link BundleJarInfo} against jars built by the real {@code BundleUtils.encryptPayloadAndCreateBundle}. */
class BundleJarInfoTest {
    @TempDir
    Path dir;

    /** Builds a bundle jar the same way client and server do; the "encryption" is a plain copy, which is fine
     *  because BundleJarInfo never looks at the payload. */
    private Path realBundle(String name, String bundleId, ECPublicKey clientIdentity, ECPublicKey clientBase,
                            ECPublicKey server) throws Exception {
        Path jar = dir.resolve(name);
        try (OutputStream os = Files.newOutputStream(jar)) {
            BundleUtils.encryptPayloadAndCreateBundle((in, out) -> in.transferTo(out), clientIdentity, clientBase,
                                                      server, bundleId,
                                                      new ByteArrayInputStream("payload".getBytes()), os);
        }
        return jar;
    }

    private Path zipWith(String name, String... entriesAndContents) throws IOException {
        Path jar = dir.resolve(name);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (int i = 0; i < entriesAndContents.length; i += 2) {
                zos.putNextEntry(new ZipEntry(entriesAndContents[i]));
                zos.write(entriesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void readsIdAndStableFingerprintFromRealBundles() throws Exception {
        ECPublicKey identity = Curve.generateKeyPair().getPublicKey();
        ECPublicKey base = Curve.generateKeyPair().getPublicKey();
        ECPublicKey server = Curve.generateKeyPair().getPublicKey();

        BundleJarInfo first = BundleJarInfo.read(realBundle("b1", "firstId-_=", identity, base, server));
        BundleJarInfo second = BundleJarInfo.read(realBundle("b2", "secondId", identity, base, server));

        assertEquals("firstId-_=", first.getBundleId());
        assertEquals("secondId", second.getBundleId());
        // Same client base key -> same fingerprint, although clientIdentity.pub differs per bundle (ephemeral key).
        assertEquals(first.getClientKeyFingerprint(), second.getClientKeyFingerprint());
        // The server derives it from the key object (or its stored clientBase.pub) and must agree.
        assertEquals(BundleJarInfo.clientKeyFingerprint(base.serialize()), first.getClientKeyFingerprint());
        assertEquals(43, first.getClientKeyFingerprint().length());
        assertTrue(first.getBundleIdEntryTimeMillis() > 0);

        BundleJarInfo other = BundleJarInfo.read(
                realBundle("b3", "thirdId", identity, Curve.generateKeyPair().getPublicKey(), server));
        assertNotEquals(first.getClientKeyFingerprint(), other.getClientKeyFingerprint());
    }

    @Test
    void fingerprintIsAValidControlClientKey() throws Exception {
        String fp = BundleJarInfo.clientKeyFingerprint(Curve.generateKeyPair().getPublicKey().serialize());
        InventoryReport r = new InventoryReport(1, 0, 0, 0, null,
                                                java.util.List.of(new InventoryReport.UpstreamEntry("id", 1, fp)),
                                                null, null);
        assertEquals(r, DropboxControlCodec.decodeInventory(DropboxControlCodec.encode(r)));
    }

    @Test
    void pemFingerprintMatchesRawAndToleratesCrlfAndWhitespace() {
        ECPublicKey base = Curve.generateKeyPair().getPublicKey();
        String pem = new String(DDDPEMEncoder.createEncodedPublicKeyBytes(base), StandardCharsets.UTF_8);
        String expected = BundleJarInfo.clientKeyFingerprint(base.serialize());
        assertEquals(expected, BundleJarInfo.clientKeyFingerprintFromPem(pem));
        assertEquals(expected, BundleJarInfo.clientKeyFingerprintFromPem("\n" + pem.replace("\n", "\r\n") + "\n  "));
        assertNull(BundleJarInfo.clientKeyFingerprintFromPem("just text"));
        assertNull(BundleJarInfo.clientKeyFingerprintFromPem("-----BEGIN X-----\n!!!\n-----END X-----"));
        assertNull(BundleJarInfo.clientKeyFingerprintFromPem(null));
    }

    @Test
    void trimsIdAndToleratesMissingKey() throws Exception {
        BundleJarInfo info = BundleJarInfo.read(zipWith("j", SecurityUtils.BUNDLEID_FILENAME, "  abc123\n"));
        assertEquals("abc123", info.getBundleId());
        assertNull(info.getClientKeyFingerprint());
    }

    @Test
    void malformedKeyGivesNullFingerprint() throws Exception {
        BundleJarInfo info = BundleJarInfo.read(
                zipWith("j", SecurityUtils.BUNDLEID_FILENAME, "abc", SecurityUtils.CLIENT_BASE_KEY, "garbage"));
        assertNull(info.getClientKeyFingerprint());
    }

    @Test
    void rejectsBadJars() throws Exception {
        Path notZip = dir.resolve("notzip");
        Files.writeString(notZip, "hello");
        assertThrows(IOException.class, () -> BundleJarInfo.read(notZip));
        assertThrows(IOException.class, () -> BundleJarInfo.read(zipWith("noid", "other", "x")));
        assertThrows(IOException.class,
                     () -> BundleJarInfo.read(zipWith("empty", SecurityUtils.BUNDLEID_FILENAME, "   ")));
        assertThrows(IOException.class,
                     () -> BundleJarInfo.read(zipWith("unclean", SecurityUtils.BUNDLEID_FILENAME, "../evil")));
        assertThrows(IOException.class, () -> BundleJarInfo.read(
                zipWith("huge", SecurityUtils.BUNDLEID_FILENAME, "a".repeat(BundleJarInfo.MAX_ENTRY_BYTES + 1))));
    }
}
