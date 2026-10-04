package net.discdd.bundledropbox.service;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECPublicKey;

import java.nio.file.Path;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

/**
 * JVM tests for {@link ServerKeysUrl}: the {@code https://discdd.net/srvr?...} URL the bundleserver's {@code /qr}
 * page encodes ({@code QRCodeController}) and BundleClient scans, plus the import round trip through
 * {@link ServerKeys#saveImported(Path, String, String, String, String)}.
 */
public class ServerKeysUrlTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Key text exactly as the server writes it: the middle line of the PEM file, base64url WITH padding. */
    private static String keyText() {
        ECPublicKey k = Curve.generateKeyPair().getPublicKey();
        return Base64.getUrlEncoder().encodeToString(k.serialize());
    }

    /** The server's own format string (QRCodeController.getQRCode). */
    private static String serverUrl(String host, int port, String id, String pre, String ratchet) {
        return String.format("https://discdd.net/srvr?host=%s&port=%d&identity=%s&signedpre=%s&ratchet=%s",
                             host, port, id, pre, ratchet);
    }

    @Test
    public void parsesTheServerQrUrlIncludingPaddedKeys() {
        String id = keyText();
        String pre = keyText();
        String ratchet = keyText();
        ServerKeysUrl u = ServerKeysUrl.parse("  " + serverUrl("192.168.1.20", 7778, id, pre, ratchet) + "\n");

        assertEquals("192.168.1.20", u.host());
        assertEquals(Integer.valueOf(7778), u.port());
        assertEquals("192.168.1.20:7778", u.origin());
        assertEquals(id, u.identity());
        assertEquals(pre, u.signedPre());
        assertEquals(ratchet, u.ratchet());
    }

    @Test
    public void paddingInsideAValueIsKept() {
        ServerKeysUrl u = ServerKeysUrl.parse("https://discdd.net/srvr?identity=YWI=&signedpre=YQ==&ratchet=YWJj");
        assertEquals("YWI=", u.identity());
        assertEquals("YQ==", u.signedPre());
        assertEquals("YWJj", u.ratchet());
    }

    @Test
    public void percentEncodedValuesAndParameterOrderAreAccepted() {
        String id = keyText();
        String url = "https://discdd.net/srvr?ratchet=" + id.replace("=", "%3D") + "&identity=" + id +
                "&signedpre=" + id + "#ignored";
        ServerKeysUrl u = ServerKeysUrl.parse(url);
        assertEquals(id, u.ratchet());
        assertNull(u.host());
        assertNull(u.port());
        assertNull(u.origin());
    }

    @Test
    public void rejectsTextThatIsNotAServerKeysUrl() {
        String k = keyText();
        assertThrows(IllegalArgumentException.class, () -> ServerKeysUrl.parse(null));
        assertThrows(IllegalArgumentException.class, () -> ServerKeysUrl.parse("   "));
        assertThrows(IllegalArgumentException.class, () -> ServerKeysUrl.parse("https://discdd.net/srvr"));
        // BundleClient accepts host+port alone; the dropbox needs all three keys.
        assertThrows(IllegalArgumentException.class,
                     () -> ServerKeysUrl.parse("https://discdd.net/srvr?host=h&port=1"));
        assertThrows(IllegalArgumentException.class,
                     () -> ServerKeysUrl.parse("https://discdd.net/srvr?identity=" + k + "&signedpre=" + k));
        assertThrows(IllegalArgumentException.class,
                     () -> ServerKeysUrl.parse(serverUrl("h", 1, k, k, k).replace("port=1", "port=x")));
        assertThrows(IllegalArgumentException.class,
                     () -> ServerKeysUrl.parse(serverUrl("h", 70000, k, k, k)));
        assertThrows(IllegalArgumentException.class,
                     () -> ServerKeysUrl.parse("https://x/srvr?identity=%zz&signedpre=" + k + "&ratchet=" + k));
    }

    @Test
    public void importedKeysRoundTripWithTheirOrigin() throws Exception {
        ServerKeysUrl u = ServerKeysUrl.parse(serverUrl("laptop.local", 7778, keyText(), keyText(), keyText()));
        Path dir = tmp.getRoot().toPath().resolve("serverKeys");

        ServerKeys saved = ServerKeys.saveImported(dir, u.identity(), u.signedPre(), u.ratchet(), u.origin());
        assertEquals("imported from laptop.local:7778", saved.describe());

        ServerKeys loaded = ServerKeys.load(dir, () -> { throw new AssertionError("bundled keys not needed"); },
                                            () -> { throw new AssertionError(); },
                                            () -> { throw new AssertionError(); });
        assertEquals(ServerKeys.Source.IMPORTED, loaded.source());
        assertEquals("laptop.local:7778", loaded.origin());
        assertEquals(saved.identityKey(), loaded.identityKey());

        ServerKeys.deleteImported(dir);
        ServerKeys bundled = ServerKeys.load(dir,
                                             () -> new java.io.ByteArrayInputStream(saved.identityPem()),
                                             () -> new java.io.ByteArrayInputStream(saved.signedPrePem()),
                                             () -> new java.io.ByteArrayInputStream(saved.ratchetPem()));
        assertEquals("built-in", bundled.describe());
    }

    @Test
    public void invalidKeyInAnOtherwiseWellFormedUrlIsRejectedOnSave() {
        String k = keyText();
        ServerKeysUrl u = ServerKeysUrl.parse(serverUrl("h", 1, "AAAA", k, k));
        assertThrows(org.whispersystems.libsignal.InvalidKeyException.class,
                     () -> ServerKeys.saveImported(tmp.getRoot().toPath().resolve("k"), u.identity(), u.signedPre(),
                                                   u.ratchet(), u.origin()));
    }
}
