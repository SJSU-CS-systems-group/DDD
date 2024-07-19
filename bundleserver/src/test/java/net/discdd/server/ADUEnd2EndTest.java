package net.discdd.server;

import io.grpc.ManagedChannelBuilder;
import net.discdd.bundlesecurity.BundleIDGenerator;
import net.discdd.bundlesecurity.DDDPEMEncoder;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.bundletransport.service.BundleServiceGrpc;
import net.discdd.model.ADU;
import net.discdd.model.Acknowledgement;
import net.discdd.model.EncryptedPayload;
import net.discdd.model.EncryptionHeader;
import net.discdd.model.Payload;
import net.discdd.model.UncompressedBundle;
import net.discdd.model.UncompressedPayload;
import net.discdd.utils.BundleUtils;
import net.discdd.utils.DDDJarFileCreator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.ratchet.AliceSignalProtocolParameters;
import org.whispersystems.libsignal.ratchet.RatchetingSession;
import org.whispersystems.libsignal.state.SessionRecord;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.jar.JarFile;
import java.util.logging.Logger;

import static net.discdd.bundlesecurity.DDDPEMEncoder.ECPrivateKeyType;
import static net.discdd.bundlesecurity.DDDPEMEncoder.ECPublicKeyType;
import static net.discdd.bundlesecurity.SecurityUtils.PAYLOAD_DIR;
import static net.discdd.bundlesecurity.SecurityUtils.PAYLOAD_FILENAME;
import static net.discdd.bundlesecurity.SecurityUtils.SIGNATURE_DIR;
import static net.discdd.bundlesecurity.SecurityUtils.SIGNATURE_FILENAME;

@SpringBootTest
public class ADUEnd2EndTest {
    private static final Logger logger = Logger.getLogger(ADUEnd2EndTest.class.getName());

    @TempDir
    static Path tempRootDir;

    @TempDir
    static Path clientSession;
    private static IdentityKeyPair serverIdentity;
    private static ECKeyPair serverSignedPreKey;
    private static ECKeyPair serverRatchetKey;

    @BeforeAll
    static void setup() throws IOException {
        System.setProperty("bundle-server.bundle-store-root", tempRootDir.toString() + '/');
        var keysDir = tempRootDir.resolve(Path.of("BundleSecurity", "Keys", "Server", "Server_Keys"));
        keysDir.toFile().mkdirs();
        System.setProperty("bundle-server.keys-dir", keysDir.toString());
        ECKeyPair keyPair = Curve.generateKeyPair();
        serverIdentity = new IdentityKeyPair(new IdentityKey(keyPair.getPublicKey()), keyPair.getPrivateKey());
        serverSignedPreKey = Curve.generateKeyPair();
        serverRatchetKey = Curve.generateKeyPair();
        Files.writeString(keysDir.resolve("serverIdentity.pub"), DDDPEMEncoder.encode(serverIdentity.getPublicKey().serialize(), ECPublicKeyType));
        Files.writeString(keysDir.resolve("serverIdentity.pvt"), DDDPEMEncoder.encode(serverIdentity.serialize(), ECPrivateKeyType));
        Files.writeString(keysDir.resolve("server_signed_pre.pub"), DDDPEMEncoder.encode(
                serverSignedPreKey.getPublicKey().serialize(), ECPublicKeyType));
        Files.writeString(keysDir.resolve("serverSignedPreKey.pvt"), DDDPEMEncoder.encode(
                serverSignedPreKey.getPrivateKey().serialize(), ECPrivateKeyType));
        Files.writeString(keysDir.resolve("server_ratchet.pub"), DDDPEMEncoder.encode(serverRatchetKey.getPublicKey().serialize(), ECPublicKeyType));
        Files.writeString(keysDir.resolve("serverRatchetKey.pvt"), DDDPEMEncoder.encode(
                serverRatchetKey.getPrivateKey().serialize(), ECPrivateKeyType));
    }

    @Test
    void test1ContextLoads() {}

    @Test
    void test2UploadBundle(@TempDir Path bundleDir) throws NoSuchAlgorithmException, IOException, InvalidKeyException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DDDJarFileCreator innerJar = new DDDJarFileCreator(payload);

        // create the keypairs for the client
        ECKeyPair identityPubKeyPair = Curve.generateKeyPair();
        IdentityKeyPair identityKeyPair = new IdentityKeyPair(new IdentityKey(identityPubKeyPair.getPublicKey()), identityPubKeyPair.getPrivateKey());
        ECKeyPair baseKeyPair = Curve.generateKeyPair();
        String clientId = SecurityUtils.generateID(identityKeyPair.getPublicKey().getPublicKey().serialize());


        // add the records to the inner jar
        Acknowledgement ackRecord = new Acknowledgement("HB");
        innerJar.createEntry("/acknowledgement.txt").write(ackRecord.toString().getBytes());
        innerJar.createEntry("/routing.metadata").write("{}".getBytes());
        innerJar.close();

        // create the signed outer jar
        DDDJarFileCreator outerJar = new DDDJarFileCreator(Files.newOutputStream(bundleDir.resolve("outer-jar.jar")));
        byte[] payloadBytes = payload.toByteArray();

        // now sign the payload
        String payloadSignature = Base64.getUrlEncoder().encodeToString(Curve.calculateSignature(identityKeyPair.getPrivateKey(),payloadBytes));
        outerJar.createEntry(Path.of(PAYLOAD_DIR, PAYLOAD_FILENAME + i + SIGNATURE_FILENAME)).write(payloadSignature.getBytes());

        // encrypt the payload
        SessionRecord sessionRecord = new SessionRecord();
        SignalProtocolAddress address = new SignalProtocolAddress(clientId, 1);
        var sessionStore = SecurityUtils.createInMemorySignalProtocolStore();
        SessionCipher sessionCipher = new SessionCipher(sessionStore, address);

        AliceSignalProtocolParameters parameters = AliceSignalProtocolParameters.newBuilder()
                .setOurBaseKey(baseKeyPair)
                .setOurIdentityKey(identityKeyPair)
                .setTheirRatchetKey(serverRatchetKey.getPublicKey())
                .setTheirSignedPreKey(serverSignedPreKey.getPublicKey())
                .setTheirIdentityKey(serverIdentity.getPublicKey())
                .create();
        RatchetingSession.initializeSession(sessionRecord.getSessionState(), parameters);
        CiphertextMessage cipherTextMessage = sessionCipher.encrypt(payloadBytes);
        var cipherTextBytes = cipherTextMessage.serialize();

        // store the encrypted payload
        outerJar.createEntry(Path.of(PAYLOAD_DIR, PAYLOAD_FILENAME + 1 + SIGNATURE_FILENAME)).write(cipherTextBytes);
        // store the bundleId
        outerJar.createEntry(SecurityUtils.BUNDLEID_FILENAME).write(bundleId.getBytes());

        // store the keys
        outerJar.createEntry(SecurityUtils.CLIENT_IDENTITY_KEY).write(identityKeyPair.serialize());
        Path clientIdentityKeyPath = innerStagingDir.resolve(SecurityUtils.CLIENT_IDENTITY_KEY);
        Path clientBaseKeyPath = innerStagingDir.resolve(SecurityUtils.CLIENT_BASE_KEY);
        Path serverIdentityKeyPath = innerStagingDir.resolve(SecurityUtils.SERVER_IDENTITY_KEY);

        SecurityUtils.createEncodedPublicKeyFile(identityKeyPair.getPublicKey().getPublicKey(), clientIdentityKeyPath);
        SecurityUtils.createEncodedPublicKeyFile(baseKeyPair.getPublicKey(), clientBaseKeyPath);
        SecurityUtils.createEncodedPublicKeyFile(serverIdentity.getPublicKey().getPublicKey(), serverIdentityKeyPath);

        EncryptedPayload encryptedPayload = new EncryptedPayload(bundleId, innerPayloadDir.toFile());
        EncryptionHeader encryptionHeader = EncryptionHeader.builder()
                .clientBaseKey(clientBaseKeyPath.toFile())
                .clientIdentityKey(clientIdentityKeyPath.toFile())
                .serverIdentityKey(serverIdentityKeyPath.toFile())
                .build();
       var uncompressedBundle = new UncompressedBundle(bundleId, bundleDir.resolve(bundleId).toFile(), encryptionHeader, encryptedPayload, innerSignatureDir.toFile());
       BundleUtils.compressBundle(uncompressedBundle, bundleDir);

       var stub = BundleServiceGrpc.newBlockingStub(ManagedChannelBuilder.forAddress("localhost", 8080).usePlaintext().build());
       //var uploadObserver = stub.uploadBundle(new BundleUploadRequest());
    }
}
