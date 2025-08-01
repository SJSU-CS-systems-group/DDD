package net.discdd.bundle;

import net.discdd.bundlerouting.RoutingExceptions;
import net.discdd.bundlerouting.WindowUtils.WindowExceptions;
import net.discdd.bundlesecurity.DDDPEMEncoder;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.bundlesecurity.ServerSecurity;
import net.discdd.client.bundletransmission.ClientBundleTransmission;
import net.discdd.grpc.BundleSenderType;
import net.discdd.pathutils.ClientPaths;
import net.discdd.server.applicationdatamanager.AduStores;
import net.discdd.server.applicationdatamanager.ServerApplicationDataManager;
import net.discdd.server.bundlerouting.BundleRouting;
import net.discdd.server.bundlerouting.ServerWindowService;
import net.discdd.server.bundlesecurity.ServerBundleSecurity;
import net.discdd.server.bundletransmission.ServerBundleTransmission;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ratchet.AliceSignalProtocolParameters;
import org.whispersystems.libsignal.ratchet.RatchetingSession;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.impl.InMemorySignalProtocolStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;

import static net.discdd.bundlesecurity.DDDPEMEncoder.ECPrivateKeyType;
import static net.discdd.bundlesecurity.DDDPEMEncoder.ECPublicKeyType;

public class BundleGenerationTest {
    @TempDir
    static Path serverRootDir;
    @TempDir
    static Path clientRootDir;
    private static IdentityKeyPair serverIdentity;
    private static Path serverIdentityKeyPath;
    private static Path serverPrivateKeyPath;
    private static Path serverSignedPreKeyPath;
    private static Path serverPrivatePreKeyPath;
    private static Path serverRatchetKeyPath;
    private static Path serverPrivateRatchetKeyPath;
    private static IdentityKeyPair clientIdentity;
    private static ECKeyPair clientBaseKeyPair;
    private static String clientId;
    private static ECKeyPair serverSignedPreKey;
    private static ECKeyPair serverRatchetKey;

    @BeforeAll
    public static void setUp() throws IOException, NoSuchAlgorithmException {
        // setup server keys
        var keysDir = serverRootDir.resolve(java.nio.file.Path.of("BundleSecurity", "Keys", "Server", "Server_Keys"));
        Assertions.assertTrue(keysDir.toFile().mkdirs());
        System.setProperty("bundle-server.keys-dir", keysDir.toString());
        ECKeyPair keyPair = Curve.generateKeyPair();
        serverIdentity = new IdentityKeyPair(new IdentityKey(keyPair.getPublicKey()), keyPair.getPrivateKey());
        serverSignedPreKey = Curve.generateKeyPair();
        serverRatchetKey = Curve.generateKeyPair();
        serverIdentityKeyPath = keysDir.resolve(SecurityUtils.SERVER_IDENTITY_KEY);
        Files.writeString(serverIdentityKeyPath,
                          DDDPEMEncoder.encode(serverIdentity.getPublicKey().serialize(), ECPublicKeyType));
        serverPrivateKeyPath = keysDir.resolve(SecurityUtils.SERVER_IDENTITY_PRIVATE_KEY);
        Files.writeString(serverPrivateKeyPath, DDDPEMEncoder.encode(serverIdentity.serialize(), ECPrivateKeyType));
        serverSignedPreKeyPath = keysDir.resolve(SecurityUtils.SERVER_SIGNED_PRE_KEY);
        Files.writeString(serverSignedPreKeyPath,
                          DDDPEMEncoder.encode(serverSignedPreKey.getPublicKey().serialize(), ECPublicKeyType));
        serverPrivatePreKeyPath = keysDir.resolve(SecurityUtils.SERVER_SIGNEDPRE_PRIVATE_KEY);
        Files.writeString(serverPrivatePreKeyPath,
                          DDDPEMEncoder.encode(serverSignedPreKey.getPrivateKey().serialize(), ECPrivateKeyType));
        serverRatchetKeyPath = keysDir.resolve(SecurityUtils.SERVER_RATCHET_KEY);
        Files.writeString(serverRatchetKeyPath,
                          DDDPEMEncoder.encode(serverRatchetKey.getPublicKey().serialize(), ECPublicKeyType));
        serverPrivateRatchetKeyPath = keysDir.resolve(SecurityUtils.SERVER_RATCHET_PRIVATE_KEY);
        Files.writeString(serverPrivateRatchetKeyPath,
                          DDDPEMEncoder.encode(serverRatchetKey.getPrivateKey().serialize(), ECPrivateKeyType));
        // set up the client keys
        // create the keypairs for the client
        var clientIdentityPubKeyPair = Curve.generateKeyPair();
        clientIdentity = new IdentityKeyPair(new IdentityKey(clientIdentityPubKeyPair.getPublicKey()),
                                             clientIdentityPubKeyPair.getPrivateKey());
        clientBaseKeyPair = Curve.generateKeyPair();
        clientId = SecurityUtils.generateID(clientIdentity.getPublicKey().getPublicKey().serialize());

    }

    @Test
    public void testSimpleEncryption() throws Exception {
        SessionRecord sessionRecord = new SessionRecord();
        SignalProtocolAddress address = new SignalProtocolAddress(clientId, 1);
        InMemorySignalProtocolStore clientSessionStore = SecurityUtils.createInMemorySignalProtocolStore();

        AliceSignalProtocolParameters aliceSignalProtocolParameters = AliceSignalProtocolParameters.newBuilder()
                .setOurBaseKey(clientBaseKeyPair)
                .setOurIdentityKey(clientIdentity)
                .setTheirOneTimePreKey(org.whispersystems.libsignal.util.guava.Optional.absent())
                .setTheirRatchetKey(serverRatchetKey.getPublicKey())
                .setTheirSignedPreKey(serverSignedPreKey.getPublicKey())
                .setTheirIdentityKey(serverIdentity.getPublicKey())
                .create();
        RatchetingSession.initializeSession(sessionRecord.getSessionState(), aliceSignalProtocolParameters);
        clientSessionStore.storeSession(address, sessionRecord);
        var clientSessionCipher = new SessionCipher(clientSessionStore, address);
        var clientPaths = new ClientPaths(clientRootDir,
                                          Files.readAllBytes(serverIdentityKeyPath),
                                          Files.readAllBytes(serverSignedPreKeyPath),
                                          Files.readAllBytes(serverPrivateKeyPath));
        var cbt = new ClientBundleTransmission(clientPaths, x -> {});
        var ss = new ServerSecurity(serverRootDir);
        var sbs = new ServerBundleSecurity(ss);
        var aduStores = new AduStores(serverRootDir);
        var sadm = new ServerApplicationDataManager(
                aduStores,
                (x,y) -> {},
                new InMemorySentAduDetailsRepository(),
                new InMemoryBundleMetadataRepository(),
                new InMemoryRegisteredBundleRepository(),
                new InMemoryClientBundleCountersRepository(),
                10000000);
        var sbr = new BundleRouting(new InMemoryServerRoutingRepository());
        var sws = new ServerWindowService(new InMemoryServerWindowRepository());

        var receivedProcessingDirectory = serverRootDir.resolve("ReceivedProcessing");
        var bundleReceivedLocation = serverRootDir.resolve("BundleReceived");
        var bundleToSendDirectory = serverRootDir.resolve("BundleToSend");
        var sbt = new ServerBundleTransmission(sbs, sadm, sbr, sws, ss, receivedProcessingDirectory, bundleReceivedLocation, bundleToSendDirectory);

        var bundleDTO = cbt.generateBundleForTransmission();
        sbt.processReceivedBundle(BundleSenderType.TRANSPORT, "testSender", bundleDTO.getBundle());
    }
}
