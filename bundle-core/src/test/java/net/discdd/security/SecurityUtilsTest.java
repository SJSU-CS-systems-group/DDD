package net.discdd.security;

import net.discdd.bundlesecurity.DDDPEMEncoder;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.tls.DDDTLSUtil;
import org.assertj.core.api.Assert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ecc.ECPublicKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static net.discdd.bundlesecurity.SecurityUtils.PUB_KEY_FOOTER;
import static net.discdd.bundlesecurity.SecurityUtils.PUB_KEY_HEADER;
import static net.discdd.bundlesecurity.SecurityUtils.PVT_KEY_FOOTER;
import static net.discdd.bundlesecurity.SecurityUtils.PVT_KEY_HEADER;
import static net.discdd.bundlesecurity.SecurityUtils.createEncodedPublicKeyBytes;
import static net.discdd.bundlesecurity.SecurityUtils.createEncryptedEncodedPublicKeyBytes;
import static net.discdd.bundlesecurity.SecurityUtils.decodeEncryptedPublicKeyfromFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SecurityUtilsTest {
    private static final Logger logger = Logger.getLogger(SecurityUtilsTest.class.getName());
    static {
        // Set up logger to print to console
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(Level.ALL);
        logger.addHandler(consoleHandler);
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
    }
    @Test
    void testSecurityUtilsTest(@TempDir Path tempDir) throws IOException, GeneralSecurityException, InvalidKeyException {
        //create a pair of client public and private identity keys
        ECKeyPair clientIdentityKeyPair = Curve.generateKeyPair();
        IdentityKeyPair clientKeyPair = new IdentityKeyPair(new IdentityKey(clientIdentityKeyPair.getPublicKey()),
                                                            clientIdentityKeyPair.getPrivateKey());
        logger.log(INFO, "here is our init client: " + Base64.getUrlEncoder().encodeToString(clientKeyPair.getPublicKey().serialize()));
        //store these in tempDir/Client_Keys
        Path clientTempDirPath = tempDir.resolve("Client_Keys");
        Files.createDirectories(clientTempDirPath);
        byte[] clientPubBytes = (PUB_KEY_HEADER + "\n" + Base64.getUrlEncoder().encodeToString(clientKeyPair.getPublicKey().serialize()) + "\n" +
                PUB_KEY_FOOTER).getBytes();
        Files.write(clientTempDirPath.resolve("clientIdentity.pub"), clientPubBytes, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

        //create a pair of server public and private identity keys
        ECKeyPair serverIdentityKeyPair = Curve.generateKeyPair();
        IdentityKeyPair serverKeyPair = new IdentityKeyPair(new IdentityKey(serverIdentityKeyPair.getPublicKey()),
                                                            serverIdentityKeyPair.getPrivateKey());

        //store these in tempDir/Server_Keys
        Path serverTempDirPath = tempDir.resolve("Server_Keys");
        Files.createDirectories(serverTempDirPath);

        byte[] encodedClient = createEncryptedEncodedPublicKeyBytes(clientIdentityKeyPair.getPublicKey(),
                                                                    serverIdentityKeyPair.getPublicKey());
        Files.write(clientTempDirPath.resolve("clientIdentity.pub"), encodedClient, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        logger.log(INFO, "here is our encoded client: " + Base64.getUrlEncoder().encodeToString(encodedClient));
        //String decodedClient = decodeEncryptedPublicKeyfromFile(serverTempDirPath.resolve("serverIdentity.pvt")); // IF SER INSTANCE IS BEING CREATED FROM PATH, WHAT KEYS ARE BEING CREATED?
        String decodedClient = decodeEncryptedPublicKeyfromFile(serverKeyPair.getPrivateKey(), clientTempDirPath.resolve("clientIdentity.pub"));
        logger.log(INFO, "here is our decoded client: " + decodedClient);

        //Assert.assertTrue(true); eventually assert og client pub key == decodedClient
        //assertTrue(true);
        assertEquals(Base64.getUrlEncoder().encodeToString(clientKeyPair.getPublicKey().serialize()), decodedClient);
    }
}
