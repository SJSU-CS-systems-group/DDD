package net.discdd.server;

import net.discdd.bugreport.BugReport;
import net.discdd.bundlesecurity.BundleIDGenerator;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.client.bundletransmission.ClientBundleTransmission;
import net.discdd.client.bundletransmission.TransportDevice;
import net.discdd.dropbox.control.BundleJarInfo;
import net.discdd.dropbox.control.DropboxControl;
import net.discdd.dropbox.control.DropboxControl.Reason;
import net.discdd.dropbox.control.DropboxControlCodec;
import net.discdd.dropbox.control.InventoryReport;
import net.discdd.dropbox.control.InventoryReport.DownstreamEntry;
import net.discdd.dropbox.control.InventoryReport.PlanResult;
import net.discdd.dropbox.control.InventoryReport.UpstreamEntry;
import net.discdd.dropbox.control.RecencyGrant;
import net.discdd.dropbox.control.RecencyRequestSigner;
import net.discdd.dropbox.control.RetentionPlan;
import net.discdd.dropbox.control.RetentionPlan.Deletion;
import net.discdd.grpc.BundleSenderType;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.model.Bundle;
import net.discdd.pathutils.ClientPaths;
import net.discdd.server.applicationdatamanager.AduStores;
import net.discdd.server.applicationdatamanager.ServerApplicationDataManager;
import net.discdd.server.bundlerouting.BundleRouting;
import net.discdd.server.bundlerouting.RoutingSource;
import net.discdd.server.bundletransmission.ServerBundleTransmission;
import net.discdd.server.repository.BundleMetadataRepository;
import net.discdd.server.repository.ClientDownstreamAckRepository;
import net.discdd.server.repository.DropboxNodeRepository;
import net.discdd.server.repository.DropboxRecencyGrantRepository;
import net.discdd.server.repository.InventorySightingRepository;
import net.discdd.server.repository.ReceivedBundleRepository;
import net.discdd.server.repository.RoutingEdgeRepository;
import net.discdd.server.repository.SentAduDetailsRepository;
import net.discdd.server.repository.compositeId.InventorySightingId;
import net.discdd.server.repository.entity.RoutingEdgeId;
import net.discdd.server.service.GrpcExecutor;
import net.discdd.tls.GrpcSecurityKey;
import net.discdd.utils.BundleUtils;
import net.discdd.utils.Constants;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;

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
 *       client identity. It uploads inventory ADUs under {@link DropboxControl#APP_ID} and recency requests under
 *       {@link DropboxControl#RECENCY_APP_ID}, and accepts its own downstream bundles, as
 *       {@code DropboxControlEndpoint} does on the phone.</li>
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
    /** every recency grant the dropbox endpoint has accepted, in arrival order */
    static final List<RecencyGrant> receivedGrants = new CopyOnWriteArrayList<>();
    static int fileCounter;
    /** a stock client's view of the dropbox when it checks the dropbox's blob */
    static final TransportDevice DROPBOX_AS_TRANSPORT = new TransportDevice() {
        @Override
        public String getDescription() {return "dropbox";}

        @Override
        public String getId() {return "dropbox";}
    };

    // facts carried from one ordered test to the next
    static String stockClientKey;
    static String upstream1;
    static String upstream2;
    static String downstream1;
    static String downstream2;
    static Path upstream4Jar;

    @Autowired
    ServerBundleTransmission serverBundleTransmission;
    @Autowired
    ServerApplicationDataManager serverApplicationDataManager;
    @Autowired
    AduStores aduStores;
    @Autowired
    DropboxNodeRepository dropboxNodeRepository;
    @Autowired
    DropboxRecencyGrantRepository dropboxRecencyGrantRepository;
    @Autowired
    ReceivedBundleRepository receivedBundleRepository;
    @Autowired
    ClientDownstreamAckRepository clientDownstreamAckRepository;
    @Autowired
    SentAduDetailsRepository sentAduDetailsRepository;
    @Autowired
    GrpcExecutor.GrpcTransactionalRunner transactionalRunner;
    @Autowired
    RoutingEdgeRepository routingEdgeRepository;
    @Autowired
    BundleRouting bundleRouting;
    @Autowired
    InventorySightingRepository inventorySightingRepository;
    @Autowired
    BundleMetadataRepository bundleMetadataRepository;

    @BeforeAll
    static void setUpDropboxEndpoint() throws Exception {
        var paths = new ClientPaths(dropboxRoot,
                                    Files.readAllBytes(serverIdentityKeyPath),
                                    Files.readAllBytes(serverSignedPreKeyPath),
                                    Files.readAllBytes(serverRatchetKeyPath));
        dropbox = new ClientBundleTransmission(paths, adu -> {
            // null marks the end of a batch; other app IDs are not the control channels
            if (adu == null) return;
            try {
                byte[] bytes = Files.readAllBytes(adu.getSource().toPath());
                if (DropboxControl.APP_ID.equals(adu.getAppId())) {
                    receivedPlans.add(DropboxControlCodec.decodePlan(bytes));
                } else if (DropboxControl.RECENCY_APP_ID.equals(adu.getAppId())) {
                    receivedGrants.add(DropboxControlCodec.decodeRecencyGrant(bytes));
                }
            } catch (Exception e) {
                throw new IllegalStateException("dropbox received an undecodable control ADU", e);
            }
        });
        dropboxId = dropbox.getBundleSecurity().getClientSecurity().getClientID();
        // BundleClient's freshness gate: a blob is new if it is newer than the last one seen from that device
        Map<String, Long> lastBlobTimestamp = new ConcurrentHashMap<>();
        dropbox.setRecencyTracker((device, response) -> {
            long ts = response.getRecencyBlob().getBlobTimestamp();
            Long previous = lastBlobTimestamp.put(device.getId(), ts);
            return previous == null || ts > previous;
        });
    }

    @Test
    void test01InventoryRegistersDropboxAndPlanArrivesInItsBundle() throws Exception {
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
    void test02UpstreamReceivedByServerAndSupersededAreDeleted() throws Exception {
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
    void test03DownstreamIsDeletedOnlyAfterTheClientAcks() throws Exception {
        downstream1 = generateBundleForClient(clientId);
        aduStores.getSendADUsStorage().addADU(clientId, TEST_APPID, "reply".getBytes(), -1);
        downstream2 = generateBundleForClient(clientId);
        assertNotEquals(downstream1, downstream2);

        // (e) the stock client's app ID list and bundle contents are untouched by the dropbox feature (every client
        // gets the bug report app ID)
        var expectedAppIds = new ArrayList<>(serverApplicationDataManager.getRegisteredAppIds());
        expectedAppIds.add(BugReport.APP_ID);
        assertEquals(expectedAppIds, serverApplicationDataManager.getRegisteredAppIds(clientId));
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
    void test04StaleAndReplayedReportsAreIgnoredAndPlansAreReplaySafe() throws Exception {
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

    /**
     * "09 Own Recency Blob Design": the dropbox asks for its own blob with a request signed by its TLS key, and the
     * grant in its next bundle passes the checks a stock client applies ({@code processRecencyBlob}) for the senderId
     * the gRPC {@code getRecencyBlob} would give the TLS certificate the dropbox presents.
     */
    @Test
    void test05RecencyRequestIsAnsweredWithTheDropboxsOwnBlob() throws Exception {
        assertFalse(serverApplicationDataManager.getRegisteredAppIds(dropboxId).contains(DropboxControl.RECENCY_APP_ID),
                    "a client gets the recency app ID only after it asks");
        assertFalse(dropbox.applicationDataManager.getRegisteredAppIds().contains(DropboxControl.RECENCY_APP_ID));
        // the endpoint unions its app IDs into the server's list (DropboxControlEndpoint), so its first request uploads
        var ids = new ArrayList<>(dropbox.applicationDataManager.getRegisteredAppIds());
        ids.add(DropboxControl.RECENCY_APP_ID);
        dropbox.applicationDataManager.setRegisteredAppIds(ids);

        dropboxUploads(DropboxControl.RECENCY_APP_ID, recencyRequest(1));

        String ownSenderId = SecurityUtils.generateID(dropboxTls().grpcCert.getPublicKey().getEncoded());
        var row = dropboxRecencyGrantRepository.findById(dropboxId).orElseThrow();
        assertEquals(1, row.lastRequestSeq);
        assertEquals(ownSenderId, row.senderId);
        assertTrue(aduStores.getReceiveADUsStorage().getAppData(dropboxId, DropboxControl.RECENCY_APP_ID).isEmpty());
        assertTrue(serverApplicationDataManager.getRegisteredAppIds(dropboxId).contains(DropboxControl.RECENCY_APP_ID));

        var grant = singleGrant(dropboxDownloadsGrants());
        assertEquals(1, grant.getRequestSeq());
        var response = GetRecencyBlobResponse.parseFrom(grant.getRecencyBlobBytes());
        assertEquals(ownSenderId, response.getRecencyBlob().getSenderId());
        assertTrue(dropbox.processRecencyBlob(DROPBOX_AS_TRANSPORT, response), "a stock client accepts the blob");
        // the server's app_ids.txt now names the recency app, so the endpoint keeps uploading requests
        assertTrue(dropbox.applicationDataManager.getRegisteredAppIds().contains(DropboxControl.RECENCY_APP_ID));
        // the stock client's app ID list is untouched
        assertFalse(serverApplicationDataManager.getRegisteredAppIds(clientId).contains(DropboxControl.RECENCY_APP_ID));
    }

    @Test
    void test06NextRequestGetsAFresherBlobAndAReSentRequestIsNotReAnswered() throws Exception {
        var sendStore = aduStores.getSendADUsStorage();
        dropboxUploads(DropboxControl.RECENCY_APP_ID, recencyRequest(2));
        long queuedId = sendStore.getLastADUIdAdded(dropboxId, DropboxControl.RECENCY_APP_ID);
        // a copy of the same request in a later bundle (another carrier, or a re-send) while grant 2 is queued
        dropboxUploads(DropboxControl.RECENCY_APP_ID, recencyRequest(2));
        assertEquals(queuedId, sendStore.getLastADUIdAdded(dropboxId, DropboxControl.RECENCY_APP_ID));

        var grant = singleGrant(dropboxDownloadsGrants());
        assertEquals(2, grant.getRequestSeq());
        var response = GetRecencyBlobResponse.parseFrom(grant.getRecencyBlobBytes());
        assertTrue(dropbox.processRecencyBlob(DROPBOX_AS_TRANSPORT, response), "fresher than grant 1's blob");
        assertEquals(2, dropboxRecencyGrantRepository.findById(dropboxId).orElseThrow().lastRequestSeq);
        // the recency channel leaves the dropbox's control (plan) registration alone
        assertTrue(serverApplicationDataManager.getRegisteredAppIds(dropboxId).contains(DropboxControl.APP_ID));
    }

    /** a carrier that uploads a bundle the server already has (a duplicate) becomes a route to its client too */
    @Test
    void test07SecondCarrierReUploadGetsRoute() throws Exception {
        upstream4Jar = stockClientBundle(4, null);
        deliverToServer(upstream4Jar, "T1");
        assertTrue(routingEdgeRepository.existsById(uploaded("T1", clientId)));
        assertFalse(routingEdgeRepository.existsById(uploaded("T2", clientId)));

        deliverToServer(upstream4Jar, "T2");
        assertTrue(routingEdgeRepository.existsById(uploaded("T2", clientId)), "the duplicate wrote an edge");
        var toT2 = inRpcTransaction(() -> serverBundleTransmission.inventoryBundlesForTransmission(
                BundleSenderType.TRANSPORT, "T2", Set.of()));
        // nothing new since the inventory built it, so this re-offers the same bundle
        String current = generateBundleForClient(clientId);
        assertEquals(List.of(current), toT2.bundlesToDownload());
    }

    /** a client uploading its own bundle is not a carrier: neither a new bundle nor a duplicate records an edge */
    @Test
    void test08ClientSenderWritesNoEdge() throws Exception {
        Path jar5 = stockClientBundle(5, null);
        deliverToServer(jar5, BundleSenderType.CLIENT, "clientSelf");
        assertTrue(receivedBundleRepository.existsById(BundleJarInfo.read(jar5).getBundleId()), "it was processed");
        deliverToServer(jar5, BundleSenderType.CLIENT, "clientSelf");
        assertFalse(routingEdgeRepository.existsById(uploaded("clientSelf", clientId)));
        assertTrue(bundleRouting.clientsForCarrier("clientSelf", System.currentTimeMillis()).isEmpty());
    }

    /** replay guard: re-uploading a bundle first received longer ago than the TTL refreshes no route */
    @Test
    void test09DuplicateOlderThanTtlDoesNotRefresh() throws Exception {
        long t1Before = routingEdgeRepository.findById(uploaded("T1", clientId)).orElseThrow().lastSeenMs;
        var received = receivedBundleRepository.findById(BundleJarInfo.read(upstream4Jar).getBundleId()).orElseThrow();
        received.receivedAtMs = System.currentTimeMillis() - bundleRouting.ttlMs() - 60_000;
        receivedBundleRepository.save(received);

        Thread.sleep(5);
        deliverToServer(upstream4Jar, "T1");
        deliverToServer(upstream4Jar, "T9");
        assertEquals(t1Before, routingEdgeRepository.findById(uploaded("T1", clientId)).orElseThrow().lastSeenMs);
        assertFalse(routingEdgeRepository.existsById(uploaded("T9", clientId)));
    }

    /**
     * replay guard: a duplicate with no receipt row (superseded before it arrived) writes an undated row, so neither
     * that upload nor a later one of the same jar makes its carrier a route
     */
    @Test
    void test09bUndatedDuplicateRefreshesNoCarrier() throws Exception {
        String bundleId = BundleJarInfo.read(upstream4Jar).getBundleId();
        receivedBundleRepository.deleteById(bundleId);

        deliverToServer(upstream4Jar, "T7");
        assertEquals(0, receivedBundleRepository.findById(bundleId).orElseThrow().receivedAtMs, "undated row");
        deliverToServer(upstream4Jar, "T8");
        assertFalse(routingEdgeRepository.existsById(uploaded("T7", clientId)));
        assertFalse(routingEdgeRepository.existsById(uploaded("T8", clientId)), "upload order makes no route");
    }

    /**
     * One-hop via routing: the stock client reports meeting the dropbox (its {@code routing.metadata} names the
     * dropbox's TLS peer ID) through one carrier; a carrier that only ever met the dropbox then gets the client's reply.
     */
    @Test
    void test10ReplyRidesCarrierThatOnlyMetDropbox() throws Exception {
        String dropboxPeerId = dropboxRecencyGrantRepository.findById(dropboxId).orElseThrow().senderId;
        long t = System.currentTimeMillis();
        deliverToServer(stockClientBundle(6, null, "{\"" + dropboxPeerId + "\":" + t + ",\"@gen\":" + t + "}"), "T1");
        assertTrue(routingEdgeRepository.existsById(reported(dropboxPeerId, clientId)));

        dropboxUploads(DropboxControl.RECENCY_APP_ID, recencyRequest(3), "T3");
        assertTrue(routingEdgeRepository.existsById(uploaded("T3", dropboxId)));
        assertFalse(routingEdgeRepository.existsById(uploaded("T3", clientId)), "T3 never carried the client");

        aduStores.getSendADUsStorage().addADU(clientId, TEST_APPID, "reply via the dropbox".getBytes(), -1);
        var toT3 = inventory("T3");
        String current = generateBundleForClient(clientId);
        assertEquals(2, toT3.bundlesToDownload().size(), () -> "the dropbox's bundle, then the client's: " + toT3);
        assertEquals(current, toT3.bundlesToDownload().get(1), "direct clients come before via clients");
    }

    @Test
    void test11UnparseableMetadataStillWritesUploaded() throws Exception {
        long reportedBefore = reportedEdgeCount(clientId);
        Path jar = stockClientBundle(7, null, "{not json");
        deliverToServer(jar, "T4");
        assertTrue(receivedBundleRepository.existsById(BundleJarInfo.read(jar).getBundleId()), "it was processed");
        assertTrue(routingEdgeRepository.existsById(uploaded("T4", clientId)));
        assertEquals(reportedBefore, reportedEdgeCount(clientId));
    }

    /** once the carrier's own edge to the dropbox is older than the TTL, the dropbox's members stop riding it */
    @Test
    void test12StaleDropboxEdgeStopsVia() throws Exception {
        var edge = routingEdgeRepository.findById(uploaded("T3", dropboxId)).orElseThrow();
        edge.lastSeenMs = System.currentTimeMillis() - bundleRouting.ttlMs() - 60_000;
        routingEdgeRepository.save(edge);

        assertEquals(List.of(), inventory("T3").bundlesToDownload());
        String dropboxPeerId = dropboxRecencyGrantRepository.findById(dropboxId).orElseThrow().senderId;
        assertTrue(routingEdgeRepository.existsById(reported(dropboxPeerId, clientId)),
                   "the client's report is still fresh; only the carrier's route to the dropbox went stale");
    }

    /**
     * DEPOSITED membership: a client that never reports its peers ({@code {}} metadata, an old client) is routed
     * through a carrier that reaches the dropbox, because the dropbox's inventory lists the client's bundle.
     */
    @Test
    void test13OldClientRoutedViaDeposit() throws Exception {
        // forget the client's report (test10) and the deposits of earlier inventories: only this inventory may route it
        String dropboxPeerId = dropboxRecencyGrantRepository.findById(dropboxId).orElseThrow().senderId;
        routingEdgeRepository.findById(reported(dropboxPeerId, clientId)).ifPresent(routingEdgeRepository::delete);
        routingEdgeRepository.findById(deposited(dropboxId, clientId)).ifPresent(routingEdgeRepository::delete);
        assertEquals(List.of(), bundleRouting.clientsForCarrier("T3", System.currentTimeMillis()));

        // the dropbox holds the client's bundle 8, which the server has not received
        String held = BundleJarInfo.read(stockClientBundle(8, null)).getBundleId();
        var node = dropboxNodeRepository.findById(dropboxId).orElseThrow();
        dropboxUploads(DropboxControl.APP_ID,
                       DropboxControlCodec.encode(report(node.lastReportSeq + 1,
                                                         node.lastPlanSeq,
                                                         List.of(new UpstreamEntry(held, 100, stockClientKey)),
                                                         List.of(),
                                                         List.of())),
                       "T3");

        long firstSeen = inventorySightingRepository.findById(new InventorySightingId(dropboxId, "UPSTREAM", held))
                .orElseThrow().firstSeenMs;
        assertEquals(firstSeen,
                     routingEdgeRepository.findById(deposited(dropboxId, clientId)).orElseThrow().lastSeenMs);
        var toT3 = inventory("T3");
        String current = generateBundleForClient(clientId);
        assertTrue(toT3.bundlesToDownload().contains(current), () -> "the client's bundle rides T3: " + toT3);
        assertFalse(routingEdgeRepository.existsById(uploaded("T3", clientId)), "T3 never carried the client");
    }

    /**
     * RT5 window bound: while no visit uploads the dropbox's bundle (so no ack arrives), the server issues no counter
     * past the dropbox's window however much it queues; the real endpoint accepts the re-offered last bundle, and
     * its ack lets the server resume.
     */
    @Test
    void test14DropboxCounterBounded() throws Exception {
        final int window = Constants.DEFAULT_WINDOW_SIZE;
        long acked = clientDownstreamAckRepository.findById(dropboxId).orElseThrow().ackedCounter;
        String last = null;
        for (int i = 0; i < window + 5; i++) {
            aduStores.getSendADUsStorage().addADU(dropboxId, TEST_APPID, ("queued" + i).getBytes(), -1);
            last = generateBundleForClient(dropboxId);
            long counter = counterOf(last);
            assertTrue(counter <= acked + window, () -> "counter " + counter + " is past the dropbox's window");
        }
        assertEquals(acked + window, counterOf(last));

        // the endpoint accepts the bound bundle (an out-of-window one would not be acked), and its next upload acks it
        dropboxDownloadsBundle();
        var node = dropboxNodeRepository.findById(dropboxId).orElseThrow();
        dropboxUploadsInventory(report(node.lastReportSeq + 1, node.lastPlanSeq, List.of(), List.of(), List.of()));
        assertEquals(acked + window, clientDownstreamAckRepository.findById(dropboxId).orElseThrow().ackedCounter);
        assertEquals(acked + window + 1, counterOf(generateBundleForClient(dropboxId)));
    }

    // ---------------------------------------------------------------- helpers

    private long counterOf(String bundleId) {
        return bundleMetadataRepository.findById(bundleId).orElseThrow().bundleCounter;
    }

    private static RoutingEdgeId uploaded(String peerId, String client) {
        return new RoutingEdgeId(peerId, client, RoutingSource.UPLOADED);
    }

    private static RoutingEdgeId reported(String peerId, String client) {
        return new RoutingEdgeId(peerId, client, RoutingSource.REPORTED);
    }

    private static RoutingEdgeId deposited(String dropboxId, String client) {
        return new RoutingEdgeId(dropboxId, client, RoutingSource.DEPOSITED);
    }

    private long reportedEdgeCount(String client) {
        return StreamSupport.stream(routingEdgeRepository.findAll().spliterator(), false)
                .filter(e -> e.id.source == RoutingSource.REPORTED && e.id.clientId.equals(client))
                .count();
    }

    /** carrier {@code carrierId}'s inventory call with nothing on the carrier yet */
    private ServerBundleTransmission.BundlesToExchange inventory(String carrierId) throws Exception {
        return inRpcTransaction(() -> serverBundleTransmission.inventoryBundlesForTransmission(
                BundleSenderType.TRANSPORT, carrierId, Set.of()));
    }

    private static GrpcSecurityKey dropboxTls() {
        return dropbox.getBundleSecurity().getClientGrpcSecurityKey();
    }

    private static byte[] recencyRequest(long requestSeq) throws Exception {
        return DropboxControlCodec.encode(RecencyRequestSigner.sign(dropboxId, requestSeq, dropboxTls().grpcKeyPair));
    }

    private static RecencyGrant singleGrant(List<RecencyGrant> grants) {
        assertEquals(1, grants.size(), () -> "expected exactly one new grant, got " + grants);
        return grants.get(0);
    }

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
        dropboxUploads(DropboxControl.APP_ID, DropboxControlCodec.encode(report));
    }

    /** The same visit with a control ADU under any app ID. */
    private void dropboxUploads(String appId, byte[] adu) throws Exception {
        dropboxUploads(appId, adu, TRANSPORT_ID);
    }

    /** {@link #dropboxUploads(String, byte[])} through carrier {@code carrierId} */
    private void dropboxUploads(String appId, byte[] adu, String carrierId) throws Exception {
        // the endpoint reuses its last bundle unless an ADU is newer than it (millisecond mtimes)
        Thread.sleep(20);
        dropbox.applicationDataManager.sendADUsStorage.addADU(null, appId, adu, -1);
        Bundle bundle = dropbox.generateBundleForTransmission();
        deliverToServer(bundle.getSource().toPath(), carrierId);
    }

    /** {@link #deliverToServer(Path, String)} by the default test carrier */
    private void deliverToServer(Path jar) throws Exception {
        deliverToServer(jar, TRANSPORT_ID);
    }

    /** Carrier {@code carrierId} uploads a bundle; the server deletes the file it processes, so it gets a copy. */
    private void deliverToServer(Path jar, String carrierId) throws Exception {
        deliverToServer(jar, BundleSenderType.TRANSPORT, carrierId);
    }

    private void deliverToServer(Path jar, BundleSenderType senderType, String senderId) throws Exception {
        Path copy = workDir.resolve("upload-" + fileCounter++);
        Files.copy(jar, copy);
        inRpcTransaction(() -> {
            serverBundleTransmission.processBundleFile(copy.toFile(), senderType, senderId);
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
        dropboxDownloadsBundle();
        return new ArrayList<>(receivedPlans.subList(before, receivedPlans.size()));
    }

    /** {@link #dropboxDownloads}, returning the recency grants the bundle carried instead */
    private List<RecencyGrant> dropboxDownloadsGrants() throws Exception {
        int before = receivedGrants.size();
        dropboxDownloadsBundle();
        return new ArrayList<>(receivedGrants.subList(before, receivedGrants.size()));
    }

    private void dropboxDownloadsBundle() throws Exception {
        String bundleId = generateBundleForClient(dropboxId);
        Path copy = workDir.resolve("download-" + fileCounter++);
        Files.copy(serverBundleTransmission.getPathForBundleToSend(dropboxId, bundleId), copy);
        dropbox.processReceivedBundle(TRANSPORT_ID, new Bundle(copy.toFile()));
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
        return stockClientBundle(counter, ackedBundleId, "{}");
    }

    /** {@link #stockClientBundle(long, String)} carrying {@code metadataJson} as its {@code routing.metadata} */
    private static Path stockClientBundle(long counter, String ackedBundleId, String metadataJson) throws Exception {
        var payload = new ByteArrayOutputStream();
        BundleUtils.createBundlePayloadForAdus(List.of(),
                                               metadataJson.getBytes(StandardCharsets.UTF_8),
                                               ackedBundleId,
                                               null,
                                               payload,
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
