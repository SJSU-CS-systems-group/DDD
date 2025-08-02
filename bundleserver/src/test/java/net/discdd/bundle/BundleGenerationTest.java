package net.discdd.bundle;

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
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static ECKeyPair serverSignedPreKey;
    private static ECKeyPair serverRatchetKey;
    private static Path serverKeysDir;

    @BeforeAll
    public static void setUp() throws IOException, NoSuchAlgorithmException {
        // setup server keys
        serverKeysDir = serverRootDir.resolve(Path.of("BundleSecurity", "Keys", "Server", "Server_Keys"));
        Assertions.assertTrue(serverKeysDir.toFile().mkdirs());
        System.setProperty("bundle-server.keys-dir", serverKeysDir.toString());
        ECKeyPair keyPair = Curve.generateKeyPair();
        serverIdentity = new IdentityKeyPair(new IdentityKey(keyPair.getPublicKey()), keyPair.getPrivateKey());
        serverSignedPreKey = Curve.generateKeyPair();
        serverRatchetKey = Curve.generateKeyPair();
        serverIdentityKeyPath = serverKeysDir.resolve(SecurityUtils.SERVER_IDENTITY_KEY);
        Files.writeString(serverIdentityKeyPath,
                          DDDPEMEncoder.encode(serverIdentity.getPublicKey().serialize(), ECPublicKeyType));
        serverPrivateKeyPath = serverKeysDir.resolve(SecurityUtils.SERVER_IDENTITY_PRIVATE_KEY);
        Files.writeString(serverPrivateKeyPath, DDDPEMEncoder.encode(serverIdentity.serialize(), ECPrivateKeyType));
        serverSignedPreKeyPath = serverKeysDir.resolve(SecurityUtils.SERVER_SIGNED_PRE_KEY);
        Files.writeString(serverSignedPreKeyPath,
                          DDDPEMEncoder.encode(serverSignedPreKey.getPublicKey().serialize(), ECPublicKeyType));
        serverPrivatePreKeyPath = serverKeysDir.resolve(SecurityUtils.SERVER_SIGNEDPRE_PRIVATE_KEY);
        Files.writeString(serverPrivatePreKeyPath,
                          DDDPEMEncoder.encode(serverSignedPreKey.getPrivateKey().serialize(), ECPrivateKeyType));
        serverRatchetKeyPath = serverKeysDir.resolve(SecurityUtils.SERVER_RATCHET_KEY);
        Files.writeString(serverRatchetKeyPath,
                          DDDPEMEncoder.encode(serverRatchetKey.getPublicKey().serialize(), ECPublicKeyType));
        serverPrivateRatchetKeyPath = serverKeysDir.resolve(SecurityUtils.SERVER_RATCHET_PRIVATE_KEY);
        Files.writeString(serverPrivateRatchetKeyPath,
                          DDDPEMEncoder.encode(serverRatchetKey.getPrivateKey().serialize(), ECPrivateKeyType));
    }

    @Test
    public void testSimpleEncryption() throws Exception {
        var clientPaths = new ClientPaths(clientRootDir,
                                          Files.readAllBytes(serverIdentityKeyPath),
                                          Files.readAllBytes(serverSignedPreKeyPath),
                                          Files.readAllBytes(serverRatchetKeyPath));
        var cbt = new ClientBundleTransmission(clientPaths, x -> {});
        var ss = ServerSecurity.getInstance(serverKeysDir.getParent());
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
        Files.createDirectories(receivedProcessingDirectory);
        var bundleReceivedLocation = serverRootDir.resolve("BundleReceived");
        Files.createDirectories(bundleReceivedLocation);
        var bundleToSendDirectory = serverRootDir.resolve("BundleToSend");
        Files.createDirectories(bundleToSendDirectory);
        var sbt = new ServerBundleTransmission(sbs, sadm, sbr, sws, ss, receivedProcessingDirectory, bundleReceivedLocation, bundleToSendDirectory);

        var bundleDTO = cbt.generateBundleForTransmission();
        sbt.processReceivedBundle(BundleSenderType.TRANSPORT, "testSender", bundleDTO.getBundle());
        var clientId = cbt.getBundleSecurity().getClientSecurity().getClientID();
        var bundleId = sbt.generateBundleForClient(clientId);
        System.out.println(bundleId);
        var bundlePath = sbt.getPathForBundleToSend(bundleId);
        Assertions.assertTrue(Files.exists(bundlePath), "Bundle should exist at " + bundlePath);
    }
}
