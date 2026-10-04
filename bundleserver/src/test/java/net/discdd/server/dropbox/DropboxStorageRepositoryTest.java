package net.discdd.server.dropbox;

import net.discdd.bundlesecurity.ServerSecurity;
import net.discdd.dropbox.control.DropboxControl;
import net.discdd.dropbox.control.DropboxControl.Reason;
import net.discdd.dropbox.control.DropboxControlCodec;
import net.discdd.dropbox.control.InventoryReport;
import net.discdd.dropbox.control.InventoryReport.DownstreamEntry;
import net.discdd.dropbox.control.InventoryReport.UpstreamEntry;
import net.discdd.dropbox.control.RetentionPlan;
import net.discdd.dropbox.control.RetentionPlan.Deletion;
import net.discdd.model.ADU;
import net.discdd.server.applicationdatamanager.AduStores;
import net.discdd.server.applicationdatamanager.ServerApplicationDataManager;
import net.discdd.server.config.BundleServerConfig;
import net.discdd.server.repository.BundleMetadataRepository;
import net.discdd.server.repository.ClientBundleCountersRepository;
import net.discdd.server.repository.ClientDownstreamAckRepository;
import net.discdd.server.repository.DropboxNodeRepository;
import net.discdd.server.repository.InventorySightingRepository;
import net.discdd.server.repository.ReceivedBundleRepository;
import net.discdd.server.repository.RegisteredAppAdapterRepository;
import net.discdd.server.repository.SentAduDetailsRepository;
import net.discdd.server.repository.compositeId.InventorySightingId;
import net.discdd.server.repository.entity.BundleMetadata;
import net.discdd.server.repository.entity.DropboxNode;
import net.discdd.server.repository.entity.InventorySighting;
import net.discdd.server.repository.entity.ReceivedBundle;
import net.discdd.server.service.GrpcExecutor;
import net.discdd.utils.StoreADUs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H2-backed tests of the dropbox storage tables, the downstream-ACK recording in
 * {@link ServerApplicationDataManager#processAcknowledgement}, and {@link DropboxControlHandler} with real
 * repositories. The handler gets a mocked ServerSecurity that knows no client, so every upstream entry the tables
 * cannot map stays unmapped; {@link #withoutServerKeysControlAdusAreLeftForTheLiveServer} covers CLI mode (none).
 */
@DataJpaTest
@Import({ GrpcExecutor.class })
class DropboxStorageRepositoryTest {
    private static final String D = "dropboxClient";
    private static final String C = "stockClient";
    @TempDir
    static Path staticTempDir;
    @TempDir
    Path tempRootDir;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("bundle-server.bundle-store-root", () -> staticTempDir.toString());
    }

    @Autowired
    ReceivedBundleRepository receivedBundleRepository;
    @Autowired
    ClientDownstreamAckRepository clientDownstreamAckRepository;
    @Autowired
    DropboxNodeRepository dropboxNodeRepository;
    @Autowired
    InventorySightingRepository inventorySightingRepository;
    @Autowired
    BundleMetadataRepository bundleMetadataRepository;
    @Autowired
    SentAduDetailsRepository sentAduDetailsRepository;
    @Autowired
    RegisteredAppAdapterRepository registeredAppAdapterRepository;
    @Autowired
    ClientBundleCountersRepository clientBundleCountersRepository;
    @Autowired
    ObjectProvider<ServerSecurity> noServerSecurity;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ServerSecurity> keysKnowingNoClient = mock(ObjectProvider.class);

    private AduStores aduStores;
    private DropboxControlHandler handler;
    private ServerApplicationDataManager dataManager;
    private long nextAduId = 1;

    @BeforeEach
    void setUp() {
        aduStores = new AduStores(tempRootDir);
        when(keysKnowingNoClient.getIfAvailable()).thenReturn(mock(ServerSecurity.class));
        handler = handler(keysKnowingNoClient);
        var cfg = new BundleServerConfig();
        cfg.getApplicationDataManager().setAppDataSizeLimit(100_000_000L);
        dataManager = new ServerApplicationDataManager(aduStores,
                                                       (a, b) -> {},
                                                       sentAduDetailsRepository,
                                                       bundleMetadataRepository,
                                                       registeredAppAdapterRepository,
                                                       clientBundleCountersRepository,
                                                       clientDownstreamAckRepository,
                                                       List.of(handler),
                                                       cfg);
    }

    private DropboxControlHandler handler(ObjectProvider<ServerSecurity> serverSecurity) {
        return new DropboxControlHandler(dropboxNodeRepository,
                                         inventorySightingRepository,
                                         receivedBundleRepository,
                                         bundleMetadataRepository,
                                         clientDownstreamAckRepository,
                                         serverSecurity,
                                         aduStores,
                                         Duration.ofDays(14));
    }

    // ---------------------------------------------------------------- repositories

    @Test
    void receivedBundleQueries() {
        assertNull(receivedBundleRepository.findMaxBundleCounterByClientId(C));
        receivedBundleRepository.save(new ReceivedBundle("U1", C, 1, "fpC", 10, "T"));
        receivedBundleRepository.save(new ReceivedBundle("U4", C, 4, "fpC", 11, "T"));
        receivedBundleRepository.save(new ReceivedBundle("Q9", "other", 9, "fpQ", 12, "T"));
        assertEquals(4L, receivedBundleRepository.findMaxBundleCounterByClientId(C));
        assertEquals(C, receivedBundleRepository.findFirstByClientKeyFingerprint("fpC").orElseThrow().clientId);
        assertTrue(receivedBundleRepository.findFirstByClientKeyFingerprint("nope").isEmpty());
    }

    @Test
    void inventorySightingsAreScopedPerDropbox() {
        inventorySightingRepository.save(new InventorySighting(new InventorySightingId(D, "UPSTREAM", "X"), 5));
        inventorySightingRepository.save(new InventorySighting(new InventorySightingId(D, "DOWNSTREAM", "X"), 6));
        inventorySightingRepository.save(new InventorySighting(new InventorySightingId("other", "UPSTREAM", "X"), 7));
        assertEquals(2, inventorySightingRepository.findByIdDropboxId(D).size());
    }

    @Test
    void dropboxNodeStoresLargeInventory() {
        var node = new DropboxNode(D, 1);
        node.lastInventoryJson = "x".repeat(3 * 1024 * 1024);
        dropboxNodeRepository.save(node);
        assertEquals(3 * 1024 * 1024, dropboxNodeRepository.findById(D).orElseThrow().lastInventoryJson.length());
    }

    @Test
    void downstreamAckRaisesAckedCounterMonotonically() throws Exception {
        bundleMetadataRepository.save(new BundleMetadata("R1", 1, C, 0));
        bundleMetadataRepository.save(new BundleMetadata("R2", 2, C, 0));
        bundleMetadataRepository.save(new BundleMetadata("Q1", 1, "other", 0));
        dataManager.processAcknowledgement(C, "HB");
        assertTrue(clientDownstreamAckRepository.findById(C).isEmpty());
        dataManager.processAcknowledgement(C, "R2");
        assertEquals(2, clientDownstreamAckRepository.findById(C).orElseThrow().ackedCounter);
        dataManager.processAcknowledgement(C, "R1"); // an older ACK never lowers it
        assertEquals(2, clientDownstreamAckRepository.findById(C).orElseThrow().ackedCounter);
        assertEquals("R2", clientDownstreamAckRepository.findById(C).orElseThrow().ackedBundleId);
        dataManager.processAcknowledgement(C, "Q1"); // another client's bundle is ignored
        dataManager.processAcknowledgement(C, "neverIssued");
        assertEquals(2, clientDownstreamAckRepository.findById(C).orElseThrow().ackedCounter);
        assertTrue(clientDownstreamAckRepository.findById("other").isEmpty());
    }

    // ---------------------------------------------------------------- handler

    private ADU aduOf(byte[] bytes) throws Exception {
        long id = nextAduId++;
        Path f = tempRootDir.resolve("adu-" + id);
        Files.write(f, bytes);
        return new ADU(f.toFile(), DropboxControl.APP_ID, id, bytes.length, D);
    }

    private ADU inventory(long seq, List<UpstreamEntry> up, List<DownstreamEntry> down) throws Exception {
        return inventory(seq, 0, up, down);
    }

    private ADU inventory(long seq, long lastAppliedPlanSeq, List<UpstreamEntry> up, List<DownstreamEntry> down)
            throws Exception {
        return aduOf(DropboxControlCodec.encode(new InventoryReport(seq, lastAppliedPlanSeq, 1000, 10, null, up, down,
                                                                    List.of())));
    }

    private List<RetentionPlan> queuedPlans() throws Exception {
        var plans = new ArrayList<RetentionPlan>();
        for (var adu : aduStores.getSendADUsStorage().getAppData(D, DropboxControl.APP_ID)) {
            plans.add(DropboxControlCodec.decodePlan(Files.readAllBytes(adu.getSource().toPath())));
        }
        return plans;
    }

    @Test
    void inventoryRegistersDropboxAndQueuesPlan() throws Exception {
        assertFalse(handler.isRegisteredFor(D));
        receivedBundleRepository.save(new ReceivedBundle("U1", C, 1, "fpC", 10, "T"));
        handler.handleUpstream(D, List.of(inventory(1, List.of(new UpstreamEntry("U1", 5, "fpC"),
                                                              new UpstreamEntry("U2", 5, null)), List.of())));
        assertTrue(handler.isRegisteredFor(D));
        assertTrue(dataManager.getRegisteredAppIds(D).contains(DropboxControl.APP_ID));
        assertFalse(dataManager.getRegisteredAppIds(C).contains(DropboxControl.APP_ID));
        assertEquals(dataManager.getRegisteredAppIds(), dataManager.getRegisteredAppIds(C));

        var plans = queuedPlans();
        assertEquals(1, plans.size());
        assertEquals(1, plans.get(0).getPlanSeq());
        assertEquals(1, plans.get(0).getBasedOnReportSeq());
        assertEquals(List.of(new Deletion("U1", Reason.RECEIVED_BY_SERVER)), plans.get(0).getDeleteUpstream());
        assertEquals(2, inventorySightingRepository.findByIdDropboxId(D).size());
    }

    @Test
    void staleReportIsIgnoredAndNewerReportReplacesQueuedPlan() throws Exception {
        handler.handleUpstream(D, List.of(inventory(5, List.of(), List.of())));
        handler.handleUpstream(D, List.of(inventory(5, List.of(), List.of()))); // replay
        handler.handleUpstream(D, List.of(inventory(3, 1, List.of(), List.of()))); // stale, plan 1 applied
        assertEquals(1, aduStores.getSendADUsStorage().getLastADUIdAdded(D, DropboxControl.APP_ID));
        assertEquals(5, dropboxNodeRepository.findById(D).orElseThrow().lastReportSeq);

        handler.handleUpstream(D, List.of(inventory(6, List.of(new UpstreamEntry("U7", 1, null)), List.of())));
        var plans = queuedPlans();
        assertEquals(1, plans.size(), "only the newest plan stays queued");
        assertEquals(2, plans.get(0).getPlanSeq());
        assertEquals(6, plans.get(0).getBasedOnReportSeq());
    }

    /** A dropbox that lost its control state restarts at a low reportSeq having applied no plan (review v2). */
    @Test
    void reportFromADropboxWithResetStateIsAcceptedAndLiftsItsCounter() throws Exception {
        handler.handleUpstream(D, List.of(inventory(5, List.of(), List.of())));
        handler.handleUpstream(D, List.of(inventory(1, 0, List.of(new UpstreamEntry("U9", 1, null)), List.of())));
        var node = dropboxNodeRepository.findById(D).orElseThrow();
        assertEquals(5, node.lastReportSeq, "the stored counter never goes back");
        assertTrue(node.lastInventoryJson.contains("U9"), "the reset report is the current inventory");
        var plan = queuedPlans().get(0);
        assertEquals(2, plan.getPlanSeq());
        assertEquals(5, plan.getBasedOnReportSeq(), "names the stored seq, so the dropbox continues above it");
    }

    /** The queue is written at once, the row only on commit: a lost row update must not reuse a planSeq. */
    @Test
    void planSeqStaysAboveThePlanStillQueued() throws Exception {
        handler.handleUpstream(D, List.of(inventory(1, List.of(), List.of())));
        handler.handleUpstream(D, List.of(inventory(2, List.of(), List.of())));
        assertEquals(2, queuedPlans().get(0).getPlanSeq());
        var node = dropboxNodeRepository.findById(D).orElseThrow();
        node.lastPlanSeq = 1; // as if the RPC that queued plan 2 rolled back its database work
        dropboxNodeRepository.save(node);

        handler.handleUpstream(D, List.of(inventory(3, List.of(), List.of())));
        assertEquals(3, queuedPlans().get(0).getPlanSeq());
    }

    @Test
    void withoutServerKeysControlAdusAreLeftForTheLiveServer() throws Exception {
        var cliHandler = handler(noServerSecurity);
        assertThrows(java.io.IOException.class,
                     () -> cliHandler.handleUpstream(D, List.of(inventory(1, List.of(), List.of()))));
        assertFalse(cliHandler.isRegisteredFor(D));

        handler.handleUpstream(D, List.of(inventory(1, List.of(), List.of())));
        aduStores.getSendADUsStorage().deleteAllFilesUpTo(D, DropboxControl.APP_ID, 1);
        receivedBundleRepository.save(new ReceivedBundle("U1", C, 1, null, 10, "T"));
        cliHandler.beforeBundleGeneration(D); // no re-plan without keys
        assertTrue(queuedPlans().isEmpty());
    }

    @Test
    void severalReportsInOneDeliveryUseTheNewest() throws Exception {
        handler.handleUpstream(D, List.of(inventory(1, List.of(), List.of()),
                                          inventory(3, List.of(), List.of()),
                                          inventory(2, List.of(), List.of())));
        var node = dropboxNodeRepository.findById(D).orElseThrow();
        assertEquals(3, node.lastReportSeq);
        assertEquals(1, node.lastPlanSeq);
    }

    @Test
    void planSeqStaysAboveTheDropboxsLastAppliedPlanAfterServerStateLoss() throws Exception {
        // A dropbox that applied plan 40 under a server whose DropboxNode row has since been lost.
        handler.handleUpstream(D, List.of(aduOf(DropboxControlCodec.encode(
                new InventoryReport(9, 40, 1000, 10, null, List.of(), List.of(), List.of())))));
        assertEquals(41, queuedPlans().get(0).getPlanSeq());
        assertEquals(41, dropboxNodeRepository.findById(D).orElseThrow().lastPlanSeq);
    }

    @Test
    void malformedAndUnknownControlAdusAreDropped() throws Exception {
        handler.handleUpstream(D, List.of(aduOf("not json".getBytes(StandardCharsets.UTF_8)),
                                          aduOf("{\"type\":\"telemetry\",\"v\":1}".getBytes(StandardCharsets.UTF_8))));
        assertFalse(handler.isRegisteredFor(D));
        assertTrue(queuedPlans().isEmpty());
    }

    @Test
    void replanBeforeBundleGenerationQueuesOnlyWhenTheDecisionChanges() throws Exception {
        bundleMetadataRepository.save(new BundleMetadata("R1", 1, C, 0));
        handler.handleUpstream(D, List.of(inventory(1, List.of(new UpstreamEntry("U1", 5, null)),
                                                    List.of(new DownstreamEntry("R1", 5, null, 1, null)))));
        assertEquals(1, queuedPlans().get(0).getPlanSeq());

        handler.beforeBundleGeneration(D); // nothing changed: idempotent
        handler.beforeBundleGeneration(C); // not a dropbox: no-op
        assertEquals(1, aduStores.getSendADUsStorage().getLastADUIdAdded(D, DropboxControl.APP_ID));

        // facts arrive after the report: U1 reaches the server, C acks R1
        receivedBundleRepository.save(new ReceivedBundle("U1", C, 1, null, 10, "T"));
        dataManager.processAcknowledgement(C, "R1");
        handler.beforeBundleGeneration(D);
        var plans = queuedPlans();
        assertEquals(1, plans.size());
        assertEquals(2, plans.get(0).getPlanSeq());
        assertEquals(1, plans.get(0).getBasedOnReportSeq());
        assertEquals(List.of(new Deletion("U1", Reason.RECEIVED_BY_SERVER)), plans.get(0).getDeleteUpstream());
        assertEquals(List.of(new Deletion("R1", Reason.CLIENT_ACKED)), plans.get(0).getDeleteDownstream());

        handler.beforeBundleGeneration(D);
        assertEquals(2, dropboxNodeRepository.findById(D).orElseThrow().lastPlanSeq);
    }

    @Test
    void sightingsKeepFirstSeenAndForgetUnlistedIds() throws Exception {
        handler.handleUpstream(D, List.of(inventory(1, List.of(new UpstreamEntry("A", 1, null),
                                                              new UpstreamEntry("B", 1, null)), List.of())));
        var firstSeenA = inventorySightingRepository.findById(new InventorySightingId(D, "UPSTREAM", "A"))
                .orElseThrow().firstSeenMs;
        Thread.sleep(5);
        handler.handleUpstream(D, List.of(inventory(2, List.of(new UpstreamEntry("A", 1, null)), List.of())));
        assertEquals(firstSeenA,
                     inventorySightingRepository.findById(new InventorySightingId(D, "UPSTREAM", "A"))
                             .orElseThrow().firstSeenMs);
        assertTrue(inventorySightingRepository.findById(new InventorySightingId(D, "UPSTREAM", "B")).isEmpty());
    }

    @Test
    void unknownBundleExpiresAfterTtl() throws Exception {
        handler.handleUpstream(D, List.of(inventory(1, List.of(), List.of(new DownstreamEntry("Z", 1, null, 0, null)))));
        assertTrue(queuedPlans().get(0).getDeleteDownstream().isEmpty());
        // age the sighting past the 14 day TTL
        inventorySightingRepository.save(new InventorySighting(new InventorySightingId(D, "DOWNSTREAM", "Z"), 0));
        handler.beforeBundleGeneration(D);
        assertEquals(List.of(new Deletion("Z", Reason.EXPIRED)), queuedPlans().get(0).getDeleteDownstream());
    }
}
