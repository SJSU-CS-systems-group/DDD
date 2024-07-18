package net.discdd.server;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static net.discdd.server.ADUEnd2EndTest.DDDPEMEncoder.ECPrivateKeyType;
import static net.discdd.server.ADUEnd2EndTest.DDDPEMEncoder.ECPublicKeyType;

@SpringBootTest
public class ADUEnd2EndTest {
    @TempDir
    static Path tempRootDir;

    /**
     * This class is a bit funky. It uses URL base64 encoding instead of the standard base64 encoding.
     */
    public class DDDPEMEncoder {

        private static final String KEY_HEADER = "-----BEGIN %s KEY-----\n";
        private static final String KEY_FOOTER = "\n-----END %s KEY-----";

        public static final String ECPrivateKeyType = "EC PRIVATE";
        public static final String ECPublicKeyType = "EC PUBLIC";

        public static String encode(byte[] bytes, String type) {
            String base64EncodedPrivateKey = Base64.getUrlEncoder().encodeToString(bytes);
            return String.format(KEY_HEADER, type) + base64EncodedPrivateKey + String.format(KEY_FOOTER, type);
        }
    }
    @BeforeAll
    static void setup() throws IOException {
        System.setProperty("bundle-server.bundle-store-root", tempRootDir.toString() + '/');
        var keysDir = tempRootDir.resolve(Path.of("BundleSecurity", "Keys", "Server", "Server_Keys"));
        keysDir.toFile().mkdirs();
        System.setProperty("bundle-server.keys-dir", keysDir.toString());
        ECKeyPair keyPair = Curve.generateKeyPair();
        IdentityKeyPair serverIdentity =
                new IdentityKeyPair(new IdentityKey(keyPair.getPublicKey()), keyPair.getPrivateKey());
        ECKeyPair serverSignedPreKey = Curve.generateKeyPair();
        ECKeyPair serverRatchetKey = Curve.generateKeyPair();
        System.out.println("****** Server Identity Public Key: " + keysDir.resolve("server_identity.pub"));
        Files.writeString(keysDir.resolve("serverIdentity.pub"), DDDPEMEncoder.encode(serverIdentity.getPublicKey().serialize(), ECPublicKeyType));
        Files.writeString(keysDir.resolve("serverIdentity.pvt"), DDDPEMEncoder.encode(serverIdentity.serialize(), ECPrivateKeyType));
        Files.writeString(keysDir.resolve("server_signed_pre.pub"), DDDPEMEncoder.encode(serverSignedPreKey.getPublicKey().serialize(), ECPublicKeyType));
        Files.writeString(keysDir.resolve("serverSignedPreKey.pvt"), DDDPEMEncoder.encode(serverSignedPreKey.getPrivateKey().serialize(), ECPrivateKeyType));
        Files.writeString(keysDir.resolve("server_ratchet.pub"), DDDPEMEncoder.encode(serverRatchetKey.getPublicKey().serialize(), ECPublicKeyType));
        Files.writeString(keysDir.resolve("serverRatchetKey.pvt"), DDDPEMEncoder.encode(serverRatchetKey.getPrivateKey().serialize(), ECPrivateKeyType));
    }

    @Test
    void test1ContextLoads() {}
}
