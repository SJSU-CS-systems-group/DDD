package net.discdd.server;

import net.discdd.bundlesecurity.BundleIDGenerator;
import net.discdd.client.bundletransmission.ClientBundleTransmission;
import net.discdd.dropbox.control.BundleJarInfo;
import net.discdd.dropbox.control.DropboxControl;
import net.discdd.dropbox.control.DropboxControl.Reason;
import net.discdd.dropbox.control.DropboxControlCodec;
import net.discdd.dropbox.control.InventoryReport;
import net.discdd.dropbox.control.InventoryReport.DownstreamEntry;
import net.discdd.dropbox.control.InventoryReport.PlanResult;
import net.discdd.dropbox.control.InventoryReport.UpstreamEntry;
import net.discdd.dropbox.control.RetentionPlan;
import net.discdd.dropbox.control.RetentionPlan.Deletion;
import net.discdd.grpc.BundleSenderType;
import net.discdd.model.Bundle;
import net.discdd.pathutils.ClientPaths;
import net.discdd.server.applicationdatamanager.AduStores;
import net.discdd.server.applicationdatamanager.ServerApplicationDataManager;
import net.discdd.server.bundletransmission.ServerBundleTransmission;
import net.discdd.server.repository.ClientDownstreamAckRepository;
import net.discdd.server.repository.DropboxNodeRepository;
import net.discdd.server.repository.ReceivedBundleRepository;
import net.discdd.server.repository.SentAduDetailsRepository;
import net.discdd.server.service.GrpcExecutor;
import net.discdd.utils.BundleUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.whispersystems.libsignal.InvalidMessageException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of server-managed dropbox storage on a real bundleserver (Spring context, H2, real
 * {@code ServerSecurity}).
 * <ul>
 *   <li>The dropbox's embedded control endpoint is a real bundle-core {@link ClientBundleTransmission} with its own
 *       client identity. It uploads inventory ADUs under {@link DropboxControl#APP_ID} and accepts its own downstream
 *       bundles, as {@code DropboxControlEndpoint} does on the phone.</li>
 *   <li>The stock client is the raw Signal client from {@link End2EndTest} ({@code clientId}); its bundles are the
 *       ones the dropbox "holds" in its inventory.</li>
 * </ul>
 * Bundles go through {@link ServerBundleTransmission#processBundleFile} as a transport upload and come back through
 * {@link ServerBundleTransmission#generateBundleForClient}, the same calls the transport gRPC service makes, so the
 * test controls the exact order of carrier syncs. Each call runs in a transaction through
 * {@link GrpcExecutor.GrpcTransactionalRunner}, as every gRPC call does on a live server (ACK processing needs one).
 * Test methods build on each other and run in name order.
 */
@SpringBootTest(classes = { BundleServerApplication.class, End2EndTest.End2EndTestInitializer.class })
@TestMethodOrder(MethodOrderer.MethodName.class)
public class DropboxControlEnd2EndTest extends End2EndTest {
    private static final String TRANSPORT_ID = "dropbox-test-transport";
    /** an upstream ID no client of this server produced; the planner must keep it (until the TTL) */
    private static final String UNKNOWN_UPSTREAM = "neverSeenByThisServer0123456789";

    @TempDir
    static Path dropboxRoot;
    @TempDir
    static Path workDir;

    static ClientBundleTransmission dropbox;
    static String dropboxId;
    /** every plan the dropbox endpoint has accepted, in arrival order (replays included) */
    static final List<RetentionPlan> receivedPlans = new CopyOnWriteArrayList<>();
    static int fileCounter;

    // facts carried from one ordered test to the next
    static String stockClientKey;
    static String upstream1;
    static String upstream2;
    static String downstream1;
    static String downstream2;

    @Autowired
    ServerBundleTransmission serverBundleTransmission;
    @Autowired
    ServerApplicationDataManager serverApplicationDataManager;
    @Autowired
    AduStores aduStores;
    @Autowired
    DropboxNodeRepository dropboxNodeRepository;
    @Autowired
    ReceivedBundleRepository receivedBundleRepository;
    @Autowired
    ClientDownstreamAckRepository clientDownstreamAckRepository;
    @Autowired
    SentAduDetailsRepository sentAduDetailsRepository;
    @Autowired
    GrpcExecutor.GrpcTransactionalRunner transactionalRunner;

    @BeforeAll
    static void setUpDropboxEndpoint() throws Exception {
        var paths = new ClientPaths(dropboxRoot,
                                    Files.readAllBytes(serverIdentityKeyPath),
                                    Files.readAllBytes(serverSignedPreKeyPath),
                                    Files.readAllBytes(serverRatchetKeyPath));
        dropbox = new ClientBundleTransmission(paths, adu -> {
            // null marks the end of a batch; other app IDs are not the control channel
            if (adu == null || !DropboxControl.APP_ID.equals(adu.getAppId())) return;
            try {
                receivedPlans.add(DropboxControlCodec.decodePlan(Files.readAllBytes(adu.getSource().toPath())));
            } catch (Exception e) {
                throw new IllegalStateException("dropbox received an undecodable control ADU", e);
            }
        });
        dropboxId = dropbox.getBundleSecurity().getClientSecurity().getClientID();
    }

    @Test
    void test1InventoryRegistersDropboxAndPlanArrivesInItsBundle() throws Exception {
        assertFalse(dropboxNodeRepository.existsById(dropboxId));
        assertFalse(serverApplicationDataManager.getRegisteredAppIds(dropboxId).contains(DropboxControl.APP_ID),
                    "a client is not a dropbox until it reports an inventory");

        dropboxUploadsInventory(report(1, 0, List.of(), List.of(), List.of()));

        var node = dropboxNodeRepository.findById(dropboxId).orElseThrow();
        assertEquals(1, node.lastReportSeq);
        assertEquals(1, node.lastPlanSeq);
        // consumed in-process: nothing is left waiting for a service adapter that does not exist
        assertTrue(aduStores.getReceiveADUsStorage().getAppData(dropboxId, DropboxControl.APP_ID).isEmpty());
        assertTrue(serverApplicationDataManager.getRegisteredAppIds(dropboxId).contains(DropboxControl.APP_ID));

        var plans = dropboxDownloads();
        assertEquals(1, plans.size());
        var plan = plans.get(0);
        assertEquals(1, plan.getPlanSeq());
        assertEquals(1, plan.getBasedOnReportSeq());
        assertTrue(plan.getDeleteUpstream().isEmpty());
        assertTrue(plan.getDeleteDownstream().isEmpty());
        // the dropbox's app_ids.txt now names the control app, so its endpoint keeps uploading inventories
        assertTrue(dropbox.applicationDataManager.getRegisteredAppIds().contains(DropboxControl.APP_ID));
    }

    @Test
    void test2UpstreamReceivedByServerAndSupersededAreDeleted() throws Exception {
        // the stock client makes bundles 1 and 2; only 2 reaches the server (via some other carrier)
        Path jar1 = stockClientBundle(1, null);
        Path jar2 = stockClientBundle(2, null);
        var info1 = BundleJarInfo.read(jar1);
        var info2 = BundleJarInfo.read(jar2);
        upstream1 = info1.getBundleId();
        upstream2 = info2.getBundleId();
        stockClientKey = info2.getClientKeyFingerprint();
        assertNotNull(stockClientKey);
        assertEquals(stockClientKey, info1.getClientKeyFingerprint());

        deliverToServer(jar2);
        var received = receivedBundleRepository.findById(upstream2).orElseThrow();
        assertEquals(clientId, received.clientId);
        assertEquals(2, received.bundleCounter);
        assertEquals(stockClientKey, received.clientKeyFingerprint);
        assertFalse(receivedBundleRepository.existsById(upstream1));

        // the dropbox holds both, plus a bundle the server cannot map to anything
        dropboxUploadsInventory(report(2,
                                       1,
                                       List.of(new UpstreamEntry(upstream1, 100, stockClientKey),
                                               new UpstreamEntry(upstream2, 100, stockClientKey),
                                               new UpstreamEntry(UNKNOWN_UPSTREAM, 100, null)),
                                       List.of(),
                                       List.of(new PlanResult(1, List.of(), List.of()))));

        var plan = single(dropboxDownloads());
        assertEquals(2, plan.getPlanSeq());
        assertEquals(2, plan.getBasedOnReportSeq());
        // bundle 1 was never received, but its ID decrypts to counter 1 <= 2 for the same client
        assertEquals(List.of(new Deletion(upstream1, Reason.SUPERSEDED),
                             new Deletion(upstream2, Reason.RECEIVED_BY_SERVER)), plan.getDeleteUpstream());
        assertTrue(plan.getDeleteDownstream().isEmpty());
    }

    @Test
    void test3DownstreamIsDeletedOnlyAfterTheClientAcks() throws Exception {
        downstream1 = generateBundleForClient(clientId);
        aduStores.getSendADUsStorage().addADU(clientId, TEST_APPID, "reply".getBytes(), -1);
        downstream2 = generateBundleForClient(clientId);
        assertNotEquals(downstream1, downstream2);

        // (e) the stock client's app ID list and bundle contents are untouched by the dropbox feature
        assertEquals(serverApplicationDataManager.getRegisteredAppIds(),
                     serverApplicationDataManager.getRegisteredAppIds(clientId));
        assertFalse(serverApplicationDataManager.getRegisteredAppIds(clientId).contains(DropboxControl.APP_ID));
        for (var id : List.of(downstream1, downstream2)) {
            assertTrue(sentAduDetailsRepository.findByBundleId(id)
                               .stream()
                               .noneMatch(d -> DropboxControl.APP_ID.equals(d.appId)));
        }

        // the dropbox applied plan 2 and now holds both replies for the stock client
        var downstream = List.of(new DownstreamEntry(downstream1, 50, stockClientKey, 1, null),
                                 new DownstreamEntry(downstream2, 60, stockClientKey, 0, null));
        dropboxUploadsInventory(report(3,
                                       2,
                                       List.of(new UpstreamEntry(UNKNOWN_UPSTREAM, 100, null)),
                                       downstream,
                                       List.of(new PlanResult(2, List.of(upstream1, upstream2), List.of()))));
        var plan = single(dropboxDownloads());
        assertEquals(3, plan.getPlanSeq());
        // bundle 1 is superseded by bundle 2, which the dropbox holds; bundle 2 is not acked yet, so it stays
        assertEquals(List.of(new Deletion(downstream1, Reason.SUPERSEDED)), plan.getDeleteDownstream());
        assertTrue(plan.getDeleteUpstream().isEmpty());

        // the stock client picks up bundle 2 and acknowledges it in an (ack-only) upstream bundle
        Path ackOnly = stockClientBundle(3, downstream2);
        String ackOnlyId = BundleJarInfo.read(ackOnly).getBundleId();
        deliverToServer(ackOnly);
        assertEquals(2, clientDownstreamAckRepository.findById(clientId).orElseThrow().ackedCounter);
        // ACK-only bundles advance the receive counter too, so retries take the safe duplicate path.
        assertTrue(receivedBundleRepository.existsById(ackOnlyId));

        // no new inventory: the server re-plans the stored report when it builds the dropbox's next bundle
        var replanned = single(dropboxDownloads());
        assertEquals(4, replanned.getPlanSeq());
        assertEquals(3, replanned.getBasedOnReportSeq());
        assertEquals(List.of(new Deletion(downstream1, Reason.CLIENT_ACKED),
                             new Deletion(downstream2, Reason.CLIENT_ACKED)), replanned.getDeleteDownstream());
    }

    @Test
    void test4StaleAndReplayedReportsAreIgnoredAndPlansAreReplaySafe() throws Exception {
        var before = dropboxNodeRepository.findById(dropboxId).orElseThrow();
        assertEquals(3, before.lastReportSeq);
        assertEquals(4, before.lastPlanSeq);
        var lastPlan = receivedPlans.get(receivedPlans.size() - 1);

        // a stale report (lower seq) that would delete something, then a re-sent copy of the current report
        dropboxUploadsInventory(report(2,
                                       4,
                                       List.of(new UpstreamEntry(UNKNOWN_UPSTREAM, 100, null)),
                                       List.of(),
                                       List.of()));
        dropboxUploadsInventory(report(3, 4, List.of(), List.of(), List.of()));
        var after = dropboxNodeRepository.findById(dropboxId).orElseThrow();
        assertEquals(3, after.lastReportSeq);
        assertEquals(4, after.lastPlanSeq);
        assertEquals(before.lastInventoryJson, after.lastInventoryJson);

        // whatever reaches the dropbox now is at most a replay of plan 4, never a new plan
        for (var plan : dropboxDownloads()) assertEquals(lastPlan, plan);
        // unchanged facts: re-planning before each bundle queues nothing, so the server re-offers the same bundle
        String bundleA = generateBundleForClient(dropboxId);
        String bundleB = generateBundleForClient(dropboxId);
        assertEquals(bundleA, bundleB);
        assertEquals(4, dropboxNodeRepository.findById(dropboxId).orElseThrow().lastPlanSeq);

        // a newer report with the same holdings gives a new plan number but the same (deterministic) deletions
        dropboxUploadsInventory(report(4,
                                       4,
                                       List.of(new UpstreamEntry(UNKNOWN_UPSTREAM, 100, null)),
                                       List.of(new DownstreamEntry(downstream1, 50, stockClientKey, 1, null),
                                               new DownstreamEntry(downstream2, 60, stockClientKey, 1, null)),
                                       List.of(new PlanResult(3, List.of(downstream1), List.of()),
                                               new PlanResult(4, List.of(downstream2), List.of()))));
        var plan = single(dropboxDownloads());
        assertEquals(5, plan.getPlanSeq());
        assertEquals(4, plan.getBasedOnReportSeq());
        assertEquals(lastPlan.getDeleteDownstream(), plan.getDeleteDownstream());
        assertEquals(lastPlan.getDeleteUpstream(), plan.getDeleteUpstream());
    }

    // ---------------------------------------------------------------- helpers

    private static InventoryReport report(long reportSeq,
                                          long lastAppliedPlanSeq,
                                          List<UpstreamEntry> upstream,
                                          List<DownstreamEntry> downstream,
                                          List<PlanResult> planResults) {
        return new InventoryReport(reportSeq,
                                   lastAppliedPlanSeq,
                                   1_000_000,
                                   upstream.size() * 100L,
                                   System.currentTimeMillis(),
                                   upstream,
                                   downstream,
                                   planResults);
    }

    /** One dropbox visit's upload: the endpoint builds its own bundle with a fresh inventory ADU; a carrier uploads it. */
    private void dropboxUploadsInventory(InventoryReport report) throws Exception {
        // the endpoint reuses its last bundle unless an ADU is newer than it (millisecond mtimes)
        Thread.sleep(20);
        dropbox.applicationDataManager.sendADUsStorage.addADU(null,
                                                             DropboxControl.APP_ID,
                                                             DropboxControlCodec.encode(report),
                                                             -1);
        Bundle bundle = dropbox.generateBundleForTransmission();
        deliverToServer(bundle.getSource().toPath());
    }

    /** A carrier uploads a bundle; the server deletes the file it processes, so it gets a copy. */
    private void deliverToServer(Path jar) throws Exception {
        Path copy = workDir.resolve("upload-" + fileCounter++);
        Files.copy(jar, copy);
        inRpcTransaction(() -> {
            serverBundleTransmission.processBundleFile(copy.toFile(), BundleSenderType.TRANSPORT, TRANSPORT_ID);
            return null;
        });
    }

    /** the server builds (or re-offers) a client's downstream bundle, as the download RPC does */
    private String generateBundleForClient(String client) throws Exception {
        return inRpcTransaction(() -> serverBundleTransmission.generateBundleForClient(client));
    }

    /** runs a server call the way the gRPC executor runs every RPC: inside one transaction */
    private <T> T inRpcTransaction(Callable<T> call) throws Exception {
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<Exception>();
        transactionalRunner.executeInTransaction(() -> {
            try {
                result.set(call.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    /**
     * A carrier fetches the dropbox's downstream bundle and the dropbox accepts it, returning the plans it carried.
     * A bundle the endpoint already accepted (the server re-offering its last bundle) carries nothing new.
     */
    private List<RetentionPlan> dropboxDownloads() throws Exception {
        int before = receivedPlans.size();
        String bundleId = generateBundleForClient(dropboxId);
        Path copy = workDir.resolve("download-" + fileCounter++);
        Files.copy(serverBundleTransmission.getPathForBundleToSend(dropboxId, bundleId), copy);
        dropbox.processReceivedBundle(TRANSPORT_ID, new Bundle(copy.toFile()));
        return new ArrayList<>(receivedPlans.subList(before, receivedPlans.size()));
    }

    private static RetentionPlan single(List<RetentionPlan> plans) {
        assertEquals(1, plans.size(), () -> "expected exactly one new plan, got " + plans);
        return plans.get(0);
    }

    /**
     * An upstream bundle of the stock client with no ADUs, like {@code End2EndTest.createBundleForAdus} but with an
     * explicit ACK record ({@code null} for a heartbeat). Bundles must be created in counter order: they share the
     * client's Signal session.
     */
    private static Path stockClientBundle(long counter, String ackedBundleId) throws Exception {
        var payload = new ByteArrayOutputStream();
        BundleUtils.createBundlePayloadForAdus(List.of(), "{}".getBytes(), ackedBundleId, null, payload,
                                               List.of(TEST_APPID));
        String encryptedId =
                encryptBundleID(BundleIDGenerator.generateBundleID(clientId, counter, BundleIDGenerator.UPSTREAM));
        Path jar = workDir.resolve("stock-" + counter);
        try (var os = Files.newOutputStream(jar, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            BundleUtils.encryptPayloadAndCreateBundle(clientSessionCipher::encrypt,
                                                      clientIdentity.getPublicKey().getPublicKey(),
                                                      baseKeyPair.getPublicKey(),
                                                      serverIdentity.getPublicKey().getPublicKey(),
                                                      encryptedId,
                                                      new ByteArrayInputStream(payload.toByteArray()),
                                                      os);
        } catch (InvalidMessageException e) {
            throw new UncheckedIOException(new IOException(e));
        }
        return jar;
    }
}
