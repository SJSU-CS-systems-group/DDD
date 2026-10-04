package net.discdd.bundledropbox.service;

import net.discdd.bundlesecurity.DDDPEMEncoder;
import net.discdd.bundlesecurity.SecurityUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECPublicKey;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** JVM tests for {@link ServerKeys}: bundled defaults, imported (QR) keys, and the identity-key check. */
public class ServerKeysTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static ECPublicKey newKey() {
        return Curve.generateKeyPair().getPublicKey();
    }

    private static ServerKeys.KeyStream stream(byte[] b) {
        return () -> new ByteArrayInputStream(b);
    }

    private static ServerKeys.KeyStream stream(ECPublicKey k) {
        return stream(DDDPEMEncoder.createEncodedPublicKeyBytes(k));
    }

    @Test
    public void loadsBundledKeysWhenNothingIsImported() throws Exception {
        ECPublicKey id = newKey();
        ServerKeys keys = ServerKeys.load(tmp.getRoot().toPath().resolve("serverKeys"), stream(id),
                                          stream(newKey()), stream(newKey()));
        assertEquals(ServerKeys.Source.BUNDLED, keys.source());
        assertEquals(id, keys.identityKey());
        assertTrue(keys.isTrustedServerKey(id.serialize()));
        assertFalse(keys.isTrustedServerKey(newKey().serialize()));
        assertFalse(keys.isTrustedServerKey(new byte[] { 1, 2, 3 }));
    }

    @Test
    public void parsesTheRealAndroidCoreIdentityKey() throws Exception {
        // The file every stock client ships with (android-core/src/main/res/raw/server_identity.pub).
        Path raw = Paths.get("../../android-core/src/main/res/raw/server_identity.pub");
        if (!Files.exists(raw)) raw = Paths.get("AndroidApps/android-core/src/main/res/raw/server_identity.pub");
        ECPublicKey key = ServerKeys.parsePublicKeyPem(Files.readAllBytes(raw));
        assertEquals(33, key.serialize().length);
    }

    @Test
    public void importedKeysWinAndSurviveReload() throws Exception {
        Path dir = tmp.getRoot().toPath().resolve("serverKeys");
        ECPublicKey laptop = newKey();
        String b64 = Base64.getUrlEncoder().encodeToString(laptop.serialize());
        String other = Base64.getUrlEncoder().encodeToString(newKey().serialize());
        ServerKeys saved = ServerKeys.saveImported(dir, b64, other, other);
        assertEquals(ServerKeys.Source.IMPORTED, saved.source());
        assertTrue(Files.exists(dir.resolve(SecurityUtils.SERVER_IDENTITY_KEY)));

        ServerKeys loaded = ServerKeys.load(dir, stream(newKey()), stream(newKey()), stream(newKey()));
        assertEquals(ServerKeys.Source.IMPORTED, loaded.source());
        assertEquals(laptop, loaded.identityKey());
        assertArrayEquals(saved.identityPem(), loaded.identityPem());
    }

    @Test
    public void aDamagedImportFallsBackToTheBundledKeys() throws Exception {
        Path dir = tmp.getRoot().toPath().resolve("serverKeys");
        Files.createDirectories(dir);
        for (String f : new String[] { SecurityUtils.SERVER_IDENTITY_KEY, SecurityUtils.SERVER_SIGNED_PRE_KEY,
                SecurityUtils.SERVER_RATCHET_KEY }) {
            Files.write(dir.resolve(f), "garbage".getBytes(StandardCharsets.UTF_8));
        }
        ECPublicKey bundled = newKey();
        ServerKeys keys = ServerKeys.load(dir, stream(bundled), stream(newKey()), stream(newKey()));
        assertEquals(ServerKeys.Source.BUNDLED, keys.source());
        assertEquals(bundled, keys.identityKey());
    }

    @Test
    public void rejectsMalformedImports() throws Exception {
        try {
            ServerKeys.saveImported(tmp.getRoot().toPath().resolve("k"), "not base64 !!", "x", "y");
            fail("expected InvalidKeyException");
        } catch (InvalidKeyException expected) {
            assertFalse(Files.exists(tmp.getRoot().toPath().resolve("k").resolve(SecurityUtils.SERVER_IDENTITY_KEY)));
        }
    }
}
