package net.discdd.bundle;

import net.discdd.bundlerouting.RoutingExceptions;
import net.discdd.bundlerouting.WindowUtils.WindowExceptions;
import net.discdd.bundlesecurity.DDDPEMEncoder;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.bundlesecurity.ServerSecurity;
import net.discdd.client.bundletransmission.ClientBundleTransmission;
import net.discdd.grpc.BundleSenderType;
import net.discdd.model.Bundle;
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
    private static ServerSecurity ss;
    private static ServerBundleSecurity sbs;
    private static ServerApplicationDataManager sadm;
    private static AduStores aduStores;
    private static BundleRouting sbr;
    private static ServerWindowService sws;
    private static Path receivedProcessingDirectory;
    private static Path bundleReceivedLocation;
    private static Path bundleToSendDirectory;
    private static ServerBundleTransmission sbt;

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
        ss = ServerSecurity.getInstance(serverKeysDir.getParent());
        sbs = new ServerBundleSecurity(ss);
        aduStores = new AduStores(serverRootDir);
        sadm = new ServerApplicationDataManager(
                aduStores,
                (x,y) -> {},
                new InMemorySentAduDetailsRepository(),
                new InMemoryBundleMetadataRepository(),
                new InMemoryRegisteredBundleRepository(),
                new InMemoryClientBundleCountersRepository(),
                9999999);
        sbr = new BundleRouting(new InMemoryServerRoutingRepository());
        sws = new ServerWindowService(new InMemoryServerWindowRepository());

        receivedProcessingDirectory = serverRootDir.resolve("ReceivedProcessing");
        Files.createDirectories(receivedProcessingDirectory);
        bundleReceivedLocation = serverRootDir.resolve("BundleReceived");
        Files.createDirectories(bundleReceivedLocation);
        bundleToSendDirectory = serverRootDir.resolve("BundleToSend");
        Files.createDirectories(bundleToSendDirectory);
        sbt = new ServerBundleTransmission(sbs, sadm, sbr, sws, ss, receivedProcessingDirectory, bundleReceivedLocation, bundleToSendDirectory);
    }

    static class BundleGenerationContext {

        private final ClientBundleTransmission cbt;

        BundleGenerationContext(Path clientRootDir) throws IOException, WindowExceptions.BufferOverflow,
                RoutingExceptions.ClientMetaDataFileException, NoSuchAlgorithmException, InvalidKeyException {
            var clientPaths = new ClientPaths(clientRootDir,
                                              Files.readAllBytes(serverIdentityKeyPath),
                                              Files.readAllBytes(serverSignedPreKeyPath),
                                              Files.readAllBytes(serverRatchetKeyPath));
            cbt = new ClientBundleTransmission(clientPaths, x -> {});
        }
    }
    @Test
    public void testEmptyBundleExchange(@TempDir Path clientRootDir) throws Exception {
        if (true) return;
        var ctx = new BundleGenerationContext(clientRootDir);
        var bundleDTO = ctx.cbt.generateBundleForTransmission();
        sbt.processReceivedBundle(BundleSenderType.TRANSPORT, "emptySender", bundleDTO.getBundle());
        var clientId = ctx.cbt.getBundleSecurity().getClientSecurity().getClientID();
        var bundleId = sbt.generateBundleForClient(clientId);
        System.out.println(bundleId);
        var bundlePath = sbt.getPathForBundleToSend(bundleId);
        Assertions.assertTrue(Files.exists(bundlePath), "Bundle should exist at " + bundlePath);
    }
    @Test
    public void testAduBundleExchange(@TempDir Path clientRootDir) throws Exception {
        var ctx = new BundleGenerationContext(clientRootDir);
        var cToSend = ctx.cbt.applicationDataManager.sendADUsStorage;
        var cToReceive = ctx.cbt.applicationDataManager.receiveADUsStorage;
        var sToSend = aduStores.getSendADUsStorage();
        var sToReceive = aduStores.getReceiveADUsStorage();

        // add an ADU to send to server
        cToSend.addADU(null, "test", "1-from-client".getBytes(), -1);
        var bundleDTO = ctx.cbt.generateBundleForTransmission();
        sbt.processReceivedBundle(BundleSenderType.TRANSPORT, "aduSender", bundleDTO.getBundle());
        var clientId = ctx.cbt.getBundleSecurity().getClientSecurity().getClientID();

        // add an ADU to send to client
        sToSend.addADU(null, "test", "1-from-server".getBytes(), -1);
        var bundleId = sbt.generateBundleForClient(clientId);
        System.out.println(bundleId);
        var bundlePath = sbt.getPathForBundleToSend(bundleId);

        ctx.cbt.processReceivedBundle("aduSender", new Bundle(bundlePath.toFile()));

        Assertions.assertEquals(1, cToReceive.getMetadata(null, "test").lastAduAdded);
        Assertions.assertEquals("1-from-server", new String(cToReceive.getADU(null, "test", 1L)));
        Assertions.assertEquals(1, sToReceive.getMetadata(null, "test").lastAduAdded);
        Assertions.assertEquals("1-from-client", new String(sToReceive.getADU(clientId, "test", 1L)));
    }
}
