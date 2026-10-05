package net.discdd.server;

import net.discdd.bundlesecurity.BundleIDGenerator;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.bundlesecurity.ServerSecurity;
import net.discdd.grpc.BundleSenderType;
import net.discdd.server.applicationdatamanager.AduStores;
import net.discdd.server.applicationdatamanager.ServerApplicationDataManager;
import net.discdd.server.bundletransmission.ServerBundleTransmission;
import net.discdd.server.repository.ReceivedBundleRepository;
import net.discdd.server.service.GrpcExecutor;
import net.discdd.utils.BundleUtils;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = { BundleServerApplication.class, End2EndTest.End2EndTestInitializer.class })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ServerReceiveRetryTest extends End2EndTest {
    @TempDir
    static Path uploads;

    @Autowired
    ServerBundleTransmission transmission;
    @Autowired
    ServerApplicationDataManager manager;
    @Autowired
    AduStores stores;
    @Autowired
    ReceivedBundleRepository receivedBundles;
    @Autowired
    GrpcExecutor.GrpcTransactionalRunner runner;
    @Autowired
    ServerSecurity security;

    @Test
    @Order(1)
    void aduWriteFailureDoesNotAuthorizeDeletionAndTheSameBundleCanRecover() throws Exception {
        var original = createBundleForAdus(List.of(1L, 2L), clientId, 1, uploads);
        var bundleId = original.getFileName().toString();
        var appFolder = stores.getReceiveADUsStorage().rootFolder.resolve(clientId).resolve(TEST_APPID);
        Files.createDirectories(appFolder.resolve("2"));

        deliverCopy(original, uploads.resolve("first-attempt"));

        assertEquals(0, manager.getBundleCountersForClient(clientId).lastReceivedBundleCounter);
        assertFalse(receivedBundles.existsById(bundleId));
        assertArrayEquals("ADU1".getBytes(), Files.readAllBytes(appFolder.resolve("1")));
        var retryPayloads = tempRootDir.resolve("BundleTransmission/received-processing/retry-payloads");
        try (var files = Files.list(retryPayloads)) {
            assertEquals(1, files.count(), "the decrypted payload must survive a store failure");
        }

        Files.delete(appFolder.resolve("2"));
        deliverCopy(original, uploads.resolve("retry"));

        assertEquals(1, manager.getBundleCountersForClient(clientId).lastReceivedBundleCounter);
        assertTrue(receivedBundles.existsById(bundleId));
        var adus = stores.getReceiveADUsStorage().getAppData(clientId, TEST_APPID);
        assertEquals(2, adus.size());
        assertArrayEquals("ADU1".getBytes(), Files.readAllBytes(adus.get(0).getSource().toPath()));
        assertArrayEquals("ADU2".getBytes(), Files.readAllBytes(adus.get(1).getSource().toPath()));
        try (var files = Files.list(retryPayloads)) {
            assertEquals(0, files.count(), "a completed bundle must not retain a plaintext retry copy");
        }
    }

    @Test
    @Order(2)
    void ackOnlyBundleRetriesAfterAcknowledgementStorageFailure() throws Exception {
        var ackId = security.createEncryptedBundleId(clientId, 1, BundleIDGenerator.DOWNSTREAM);
        var send = stores.getSendADUsStorage();
        send.addADU(clientId, TEST_APPID, "server reply".getBytes(), 1);
        manager.fetchADUsToSend(ackId, 1, 0, clientId);
        var metadata = send.rootFolder.resolve(clientId).resolve(TEST_APPID).resolve("metadata.json");
        Files.delete(metadata);
        Files.createDirectory(metadata); // processAcknowledgement cannot update the deletion watermark

        var original = createAckOnlyBundle(2, ackId);
        var bundleId = encryptBundleID(BundleIDGenerator.generateBundleID(clientId, 2,
                                                                            BundleIDGenerator.UPSTREAM));
        deliverCopy(original, uploads.resolve("ack-first-attempt"));
        assertEquals(1, manager.getBundleCountersForClient(clientId).lastReceivedBundleCounter);
        assertFalse(receivedBundles.existsById(bundleId));

        Files.delete(metadata);
        deliverCopy(original, uploads.resolve("ack-retry"));
        assertEquals(2, manager.getBundleCountersForClient(clientId).lastReceivedBundleCounter);
        assertTrue(receivedBundles.existsById(bundleId));
    }

    @Test
    @Order(3)
    void transactionRollbackKeepsTheDecryptedPayloadForRetry() throws Exception {
        var original = createAckOnlyBundle(3, "HB");
        var bundleId = encryptBundleID(BundleIDGenerator.generateBundleID(clientId, 3,
                                                                            BundleIDGenerator.UPSTREAM));
        var first = uploads.resolve("rolled-back-attempt");
        Files.copy(original, first);
        assertThrows(IllegalStateException.class, () -> runner.executeInTransaction(() -> {
            transmission.processBundleFile(first.toFile(), BundleSenderType.TRANSPORT, "retry-test-transport");
            throw new IllegalStateException("force rollback after processing");
        }));
        assertEquals(2, manager.getBundleCountersForClient(clientId).lastReceivedBundleCounter);
        assertFalse(receivedBundles.existsById(bundleId));
        var retryPayloads = tempRootDir.resolve("BundleTransmission/received-processing/retry-payloads");
        try (var files = Files.list(retryPayloads)) {
            assertEquals(1, files.count(), "a rolled-back receipt must retain its retry payload");
        }

        deliverCopy(original, uploads.resolve("after-rollback"));
        assertEquals(3, manager.getBundleCountersForClient(clientId).lastReceivedBundleCounter);
        assertTrue(receivedBundles.existsById(bundleId));
        try (var files = Files.list(retryPayloads)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    @Order(4)
    void stagePromotionFailureKeepsAuthenticatedOutputForRetry() throws Exception {
        var original = createAckOnlyBundle(4, "HB");
        var bundleId = encryptBundleID(BundleIDGenerator.generateBundleID(clientId, 4,
                                                                            BundleIDGenerator.UPSTREAM));
        Path stage = stagePath(original, bundleId);
        Files.createDirectories(stage);
        Files.writeString(stage.resolve("blocker"), "prevents promotion");

        var first = uploads.resolve("promotion-failure");
        Files.copy(original, first);
        assertThrows(IllegalStateException.class, () -> runner.executeInTransaction(() -> {
            transmission.processBundleFile(first.toFile(), BundleSenderType.TRANSPORT, "retry-test-transport");
            throw new IllegalStateException("force rollback after promotion failure");
        }));
        assertEquals(3, manager.getBundleCountersForClient(clientId).lastReceivedBundleCounter);
        assertFalse(receivedBundles.existsById(bundleId));
        assertTrue(Files.isRegularFile(stage.getParent().resolve(bundleId + SecurityUtils.DECRYPTED_FILE_EXT)),
                   "an authenticated decrypt must remain available when promotion fails");
        assertTrue(Files.isRegularFile(stage.getParent().resolve("authenticated.sha256")));

        Files.delete(stage.resolve("blocker"));
        Files.delete(stage);
        deliverCopy(original, uploads.resolve("promotion-retry"));
        assertEquals(4, manager.getBundleCountersForClient(clientId).lastReceivedBundleCounter);
        assertTrue(receivedBundles.existsById(bundleId));
    }

    @Test
    @Order(5)
    void concurrentBundlesForOneClientKeepTheCommittedReceiveOrder() throws Exception {
        var first = createAckOnlyBundle(5, "HB");
        var second = createAckOnlyBundle(6, "HB");
        var firstId = encryptBundleID(BundleIDGenerator.generateBundleID(clientId, 5,
                                                                          BundleIDGenerator.UPSTREAM));
        var secondId = encryptBundleID(BundleIDGenerator.generateBundleID(clientId, 6,
                                                                           BundleIDGenerator.UPSTREAM));
        var firstUpload = uploads.resolve("concurrent-first");
        var secondUpload = uploads.resolve("concurrent-second");
        Files.copy(first, firstUpload);
        Files.copy(second, secondUpload);

        var firstProcessed = new CountDownLatch(1);
        var allowFirstCommit = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var firstResult = pool.submit(() -> runner.executeInTransaction(() -> {
                transmission.processBundleFile(firstUpload.toFile(), BundleSenderType.TRANSPORT,
                                               "retry-test-transport");
                firstProcessed.countDown();
                await(allowFirstCommit);
            }));
            assertTrue(firstProcessed.await(10, TimeUnit.SECONDS));

            var secondResult = pool.submit(() -> runner.executeInTransaction(() -> {
                secondStarted.countDown();
                transmission.processBundleFile(secondUpload.toFile(), BundleSenderType.TRANSPORT,
                                               "retry-test-transport");
            }));
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> secondResult.get(250, TimeUnit.MILLISECONDS),
                         "the second receive must wait for the first transaction to finish");
            allowFirstCommit.countDown();
            firstResult.get(10, TimeUnit.SECONDS);
            secondResult.get(10, TimeUnit.SECONDS);
        } finally {
            allowFirstCommit.countDown();
            pool.shutdownNow();
        }

        assertEquals(6, manager.getBundleCountersForClient(clientId).lastReceivedBundleCounter);
        assertTrue(receivedBundles.existsById(firstId));
        assertTrue(receivedBundles.existsById(secondId));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timed out waiting for commit");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for commit", e);
        }
    }

    private Path stagePath(Path uploadedJar, String bundleId) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update((clientId + "\0" + bundleId + "\0").getBytes(UTF_8));
        try (var input = new DigestInputStream(Files.newInputStream(uploadedJar), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return tempRootDir.resolve("BundleTransmission/received-processing/retry-payloads")
                .resolve(HexFormat.of().formatHex(digest.digest()))
                .resolve("payload.jar");
    }

    private Path createAckOnlyBundle(long counter, String ackId) throws Exception {
        var payload = new ByteArrayOutputStream();
        BundleUtils.createBundlePayloadForAdus(List.of(), "{}".getBytes(), ackId, null, payload,
                                               List.of(TEST_APPID));
        var encryptedId = encryptBundleID(BundleIDGenerator.generateBundleID(clientId, (int) counter,
                                                                              BundleIDGenerator.UPSTREAM));
        Path jar = uploads.resolve("ack-only-original-" + counter);
        try (var output = Files.newOutputStream(jar, StandardOpenOption.CREATE_NEW)) {
            BundleUtils.encryptPayloadAndCreateBundle(clientSessionCipher::encrypt,
                                                      clientIdentity.getPublicKey().getPublicKey(),
                                                      baseKeyPair.getPublicKey(),
                                                      serverIdentity.getPublicKey().getPublicKey(),
                                                      encryptedId,
                                                      new ByteArrayInputStream(payload.toByteArray()),
                                                      output);
        }
        return jar;
    }

    private void deliverCopy(Path original, Path copy) throws Exception {
        Files.copy(original, copy);
        runner.executeInTransaction(() -> transmission.processBundleFile(copy.toFile(),
                                                                          BundleSenderType.TRANSPORT,
                                                                          "retry-test-transport"));
    }
}
