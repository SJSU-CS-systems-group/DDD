package net.discdd.bundledropbox.visit;

import net.discdd.bundledropbox.store.BundleRecord;
import net.discdd.bundledropbox.store.Direction;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.RecencyBlob;
import net.discdd.grpc.RecencyBlobStatus;
import net.discdd.tls.GrpcSecurityKey;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JVM tests of the control-channel splice in {@link TransportVisitClient} (06 Storage Management Design "Visit
 * flow"), against {@link FakeCarrier} over real TLS and a scripted {@link ControlChannel}:
 * <ul>
 *   <li>the dropbox's own bundle is uploaded <b>first</b>, before relayed bundles, and is neither stored nor
 *       recorded in the ledger;</li>
 *   <li>downloaded bundles addressed to the dropbox go to {@link ControlChannel#accept} and <b>never</b> into the
 *       relay store, while the carrier's blob is only checked ({@link VisitResult#carrierTrusted()}) and never
 *       offered to the recency store (09 Own Recency Blob Design);</li>
 *   <li>after a processed plan the regenerated own bundle (carrying the ack) is uploaded at the end;</li>
 *   <li>a failing control channel never breaks the relay;</li>
 *   <li>a carrier that synced with our server is first asked for the own bundle by id ({@link
 *       ControlChannel#ownProbeIds()}), before any PSI; a miss leaves download-all as it was (Research/R11 RT8).</li>
 * </ul>
 */
public class TransportVisitControlTest {
    private static final String CARRIER = "carrier-T1";
    private static final String SENDER_ID = "sha1-of-T";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FakeCarrier carrier;
    private FakeRelayStore store;
    private FakeLedgerAndRecency.Ledger ledger;
    private FakeControl control;
    private FakeLedgerAndRecency.Recency recency;
    private TransportVisitClient client;
    private Path fixtures;

    /**
     * Scripted control channel: queued own bundles, an ownership table, recorded accepts, fixed probe ids. Like the
     * real endpoint, an accepted bundle is {@code MINE_ALREADY_PROCESSED} afterwards.
     */
    private static final class FakeControl implements ControlChannel {
        final Deque<OwnBundle> ownBundles = new ArrayDeque<>();
        OwnBundle lastOwn;
        final Map<String, Ownership> ownership = new HashMap<>();
        final List<String> acceptedIds = new ArrayList<>();
        final List<byte[]> acceptedBytes = new ArrayList<>();
        final List<String> probeIds = new ArrayList<>();
        /** The carrier's {@code psiExchange} count at each accept. */
        final List<Integer> psiAtAccept = new ArrayList<>();
        IntSupplier psiCount = () -> -1;
        int ownBundleCalls;
        boolean explode;

        @Override
        public synchronized List<String> ownProbeIds() {
            return new ArrayList<>(probeIds);
        }

        @Override
        public synchronized OwnBundle ownBundleForVisit() {
            ownBundleCalls++;
            if (explode) throw new IllegalStateException("boom");
            if (!ownBundles.isEmpty()) lastOwn = ownBundles.poll();
            return lastOwn;
        }

        @Override
        public synchronized Ownership classify(String bundleId) {
            if (explode) throw new IllegalStateException("boom");
            return ownership.getOrDefault(bundleId, Ownership.NOT_MINE);
        }

        @Override
        public synchronized boolean accept(Path stagedJar) {
            try {
                acceptedBytes.add(Files.readAllBytes(stagedJar));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            String id = TransportVisitClient.readJarBundleId(stagedJar);
            acceptedIds.add(id);
            psiAtAccept.add(psiCount.getAsInt());
            ownership.put(id, Ownership.MINE_ALREADY_PROCESSED);
            return true;
        }
    }

    @Before
    public void setUp() throws Exception {
        carrier = new FakeCarrier(tmp.newFolder("carrier").toPath());
        store = new FakeRelayStore(tmp.newFolder("dropbox").toPath());
        ledger = new FakeLedgerAndRecency.Ledger();
        control = new FakeControl();
        control.psiCount = carrier.psiExchanges::get;
        recency = new FakeLedgerAndRecency.Recency();
        fixtures = tmp.newFolder("fixtures").toPath();
        GrpcSecurityKey dropboxKey = new GrpcSecurityKey(tmp.newFolder("GrpcSecurity").toPath(), SecurityUtils.SERVER);
        client = new TransportVisitClient(store,
                                          ledger,
                                          recency::isServerSigned,
                                          control,
                                          CarrierChannelFactory.tls(dropboxKey),
                                          VisitOptions.DEFAULTS.withProbe(3, 50),
                                          System::currentTimeMillis);
        carrier.recency = GetRecencyBlobResponse.newBuilder()
                .setStatus(RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS)
                .setRecencyBlob(RecencyBlob.newBuilder().setBlobTimestamp(1_000).setSenderId(SENDER_ID))
                .build();
    }

    @After
    public void tearDown() throws Exception {
        carrier.close();
    }

    private VisitResult visit() {
        return client.visit(InetAddress.getLoopbackAddress(), carrier.port, CARRIER, () -> true);
    }

    private ControlChannel.OwnBundle own(String id) throws IOException {
        Path jar = BundleFixtures.bundleJar(fixtures.resolve("own"), id, id, 64);
        return new ControlChannel.OwnBundle(id, jar);
    }

    private List<String> downstreamIds() {
        return store.list(Direction.DOWNSTREAM).stream().map(BundleRecord::bundleId).sorted()
                .collect(Collectors.toList());
    }

    @Test(timeout = 60_000)
    public void ownBundleIsUploadedFirstAndNeverStoredOrLedgered() throws Exception {
        store.seed(Direction.UPSTREAM, BundleFixtures.bundleJar(fixtures, "up1", "up1", 300));
        store.seed(Direction.UPSTREAM, BundleFixtures.bundleJar(fixtures, "up2", "up2", 300));
        ControlChannel.OwnBundle own = own("own1");
        control.ownBundles.add(own);

        VisitResult r = visit();

        assertTrue(r.toString(), r.ok());
        assertEquals(List.of("own1", "up2", "up1"), carrier.uploadOrder);
        assertEquals(1, r.controlUploaded());
        assertEquals(2, r.uploaded()); // relayed bundles only
        assertArrayEquals(Files.readAllBytes(own.path()), Files.readAllBytes(carrier.toServer.resolve("own1")));
        assertFalse(ledger.wasForwardedTo("own1", CARRIER));
        assertFalse(store.contains(Direction.UPSTREAM, "own1"));
    }

    @Test(timeout = 60_000)
    public void sameOwnBundleIsNotResentToTheSameCarrier() throws Exception {
        control.ownBundles.add(own("own1"));
        assertEquals(1, visit().controlUploaded());

        VisitResult again = visit(); // fake keeps returning own1
        assertEquals(0, again.controlUploaded());
        assertEquals(List.of("own1"), carrier.uploadOrder);

        control.ownBundles.add(own("own2")); // a new report (or new ack) makes a new bundle
        assertEquals(1, visit().controlUploaded());
        assertEquals(List.of("own1", "own2"), carrier.uploadOrder);
    }

    @Test(timeout = 60_000)
    public void bundlesAddressedToTheDropboxGoToTheEndpointNeverToTheStore() throws Exception {
        BundleFixtures.bundleJar(carrier.toClient, "d1", "d1", 100);
        Path mine = BundleFixtures.bundleJar(carrier.toClient, "mine1", "mine1", 120);
        BundleFixtures.bundleJar(carrier.toClient, "old1", "old1", 80);
        control.ownership.put("mine1", ControlChannel.Ownership.MINE_NEW);
        control.ownership.put("old1", ControlChannel.Ownership.MINE_ALREADY_PROCESSED);
        control.ownBundles.add(own("own1"));
        control.ownBundles.add(own("own2")); // regenerated after the plan: acks mine1

        VisitResult r = visit();

        assertTrue(r.toString(), r.ok());
        assertEquals(List.of("d1"), downstreamIds());
        assertEquals(List.of("mine1"), control.acceptedIds);
        assertArrayEquals(Files.readAllBytes(mine), control.acceptedBytes.get(0));
        assertEquals(1, r.controlProcessed());
        assertEquals(1, r.downloaded());
        assertEquals(1, r.skipped()); // old1: the server re-offers its newest bundle
        // Own bundle first, then (no relayed uploads) the regenerated one carrying the plan ack, at the end.
        assertEquals(List.of("own1", "own2"), carrier.uploadOrder);
        assertEquals(2, r.controlUploaded());
        // Staged copies of own bundles were discarded.
        try (var staged = Files.list(tmp.getRoot().toPath().resolve("dropbox").resolve("staging"))) {
            assertEquals(0, staged.count());
        }
    }

    @Test(timeout = 60_000)
    public void noAckReuploadWhenNoPlanArrived() throws Exception {
        BundleFixtures.bundleJar(carrier.toClient, "d1", "d1", 100);
        control.ownBundles.add(own("own1"));

        VisitResult r = visit();

        assertEquals(1, control.ownBundleCalls);
        assertEquals(0, r.controlProcessed());
        assertEquals(List.of("own1"), carrier.uploadOrder);
    }

    @Test(timeout = 60_000)
    public void failingControlChannelDoesNotBreakTheRelay() throws Exception {
        store.seed(Direction.UPSTREAM, BundleFixtures.bundleJar(fixtures, "up1", "up1", 300));
        BundleFixtures.bundleJar(carrier.toClient, "d1", "d1", 100);
        control.explode = true;

        VisitResult r = visit();

        assertTrue(r.toString(), r.ok());
        assertEquals(List.of("up1"), carrier.uploadOrder);
        assertEquals(List.of("d1"), downstreamIds());
        assertEquals(0, r.controlUploaded());
    }

    /** Full box: the plan bundle must still arrive, or nothing ever frees the space (review v2, critical). */
    @Test(timeout = 60_000)
    public void fullQuotaStillFetchesTheOwnBundleAndRelaysNothing() throws Exception {
        for (int i = 0; i < 4; i++) BundleFixtures.bundleJar(carrier.toClient, "d" + i, "d" + i, 100);
        BundleFixtures.bundleJar(carrier.toClient, "mine1", "mine1", 120);
        control.ownership.put("mine1", ControlChannel.Ownership.MINE_NEW);
        store.hasCapacity = false;

        VisitResult r = visit();

        assertEquals(List.of("mine1"), control.acceptedIds);
        assertEquals(1, r.controlProcessed());
        assertEquals(List.of(), downstreamIds());
        assertEquals(0, r.downloaded());
        try (var staged = Files.list(tmp.getRoot().toPath().resolve("dropbox").resolve("staging"))) {
            assertEquals(0, staged.count());
        }

        // Once the plan freed space, the relayed bundles passed over in control-only mode are fetched.
        store.hasCapacity = true;
        control.ownership.put("mine1", ControlChannel.Ownership.MINE_ALREADY_PROCESSED);
        visit();
        assertEquals(List.of("d0", "d1", "d2", "d3"), downstreamIds());
    }

    /** Near-full box: a relayed bundle that does not fit must not hide the own bundle behind it. */
    @Test(timeout = 60_000)
    public void bundleOverQuotaSwitchesToControlOnly() throws Exception {
        BundleFixtures.bundleJar(carrier.toClient, "big", "big", 40_000);
        BundleFixtures.bundleJar(carrier.toClient, "mine1", "mine1", 120);
        control.ownership.put("mine1", ControlChannel.Ownership.MINE_NEW);
        store.downstreamQuotaBytes = 10_000;

        VisitResult r = visit();

        assertEquals(List.of("mine1"), control.acceptedIds);
        assertEquals(List.of(), downstreamIds());
        assertEquals(1, r.controlProcessed());
    }

    /** The trusted carrier's blob only sets carrierTrusted; it is never stored. */
    @Test(timeout = 60_000)
    public void trustedCarrierBlobIsOnlyChecked() throws Exception {
        BundleFixtures.bundleJar(carrier.toClient, "mine1", "mine1", 120);
        control.ownership.put("mine1", ControlChannel.Ownership.MINE_NEW);

        VisitResult r = visit();

        assertTrue(r.carrierTrusted());
        assertEquals(List.of("mine1"), control.acceptedIds);
        assertEquals("never offered", 0, recency.offers);
        assertFalse(recency.current().isPresent());
    }

    /** A carrier that never synced is not trusted, but the own bundle it carries is still accepted. */
    @Test(timeout = 60_000)
    public void untrustedCarrierStillDeliversTheOwnBundle() throws Exception {
        carrier.recency = GetRecencyBlobResponse.getDefaultInstance(); // carrier never synced
        BundleFixtures.bundleJar(carrier.toClient, "mine1", "mine1", 120);
        control.ownership.put("mine1", ControlChannel.Ownership.MINE_NEW);

        VisitResult r = visit();

        assertFalse(r.carrierTrusted());
        assertEquals(List.of("mine1"), control.acceptedIds);
    }

    // ------------------------------------------------------------------ own bundle by id (Research/R11 RT8)

    /** Five foreign bundles plus the own one: the own one is accepted by id before the first psiExchange. */
    @Test(timeout = 60_000)
    public void probeFindsOwnBundleBeforeAnyPsi() throws Exception {
        for (int i = 0; i < 5; i++) BundleFixtures.bundleJar(carrier.toClient, "d" + i, "d" + i, 100);
        BundleFixtures.bundleJar(carrier.toClient, "own-7", "own-7", 120);
        control.ownership.put("own-7", ControlChannel.Ownership.MINE_NEW);
        control.probeIds.addAll(List.of("own-9", "own-8", "own-7", "own-6"));

        VisitResult r = visit();

        assertTrue(r.toString(), r.ok());
        assertEquals(List.of("own-7"), control.acceptedIds);
        assertEquals("accepted before download-all started", List.of(0), control.psiAtAccept);
        assertTrue(r.ownByProbe());
        assertEquals("stops at the first hit", 3, carrier.byIdDownloads.get());
        assertEquals(1, r.controlProcessed());
        // Download-all is unchanged: every foreign bundle is relayed, the own one is seen again and dropped.
        assertEquals(List.of("d0", "d1", "d2", "d3", "d4"), downstreamIds());
        assertEquals(1, r.skipped());
        assertEquals(6, r.carrierBundleCount());
        assertEquals(carrier.psiExchanges.get(), r.psiExchanges());
    }

    /** No probed id is on the carrier: NOT_FOUND is no error and download-all still finds the own bundle. */
    @Test(timeout = 60_000)
    public void probeMissFallsBackToDownloadAll() throws Exception {
        BundleFixtures.bundleJar(carrier.toClient, "d1", "d1", 100);
        BundleFixtures.bundleJar(carrier.toClient, "mine1", "mine1", 120);
        control.ownership.put("mine1", ControlChannel.Ownership.MINE_NEW);
        control.probeIds.addAll(List.of("own-2", "own-1"));

        VisitResult r = visit();

        assertTrue(r.toString(), r.ok());
        assertEquals(2, carrier.byIdDownloads.get());
        assertFalse(r.ownByProbe());
        assertEquals(List.of("mine1"), control.acceptedIds);
        assertTrue("found by download-all", control.psiAtAccept.get(0) > 0);
        assertEquals(List.of("d1"), downstreamIds());
        assertEquals(1, r.controlProcessed());
    }

    /** Full box, own bundle already fetched by id: control-only mode has nothing to look for and ends at once. */
    @Test(timeout = 60_000)
    public void controlOnlyEndsAfterProbeHit() throws Exception {
        for (int i = 0; i < 4; i++) BundleFixtures.bundleJar(carrier.toClient, "d" + i, "d" + i, 100);
        BundleFixtures.bundleJar(carrier.toClient, "own-3", "own-3", 120);
        control.ownership.put("own-3", ControlChannel.Ownership.MINE_NEW);
        control.probeIds.add("own-3");
        store.hasCapacity = false;

        VisitResult r = visit();

        assertEquals(List.of("own-3"), control.acceptedIds);
        assertTrue(r.ownByProbe());
        assertEquals("no control-only walk", 0, carrier.psiDownloads.get());
        assertEquals(1, r.psiExchanges()); // the counting exchange only
        assertEquals(List.of(), downstreamIds());
        assertTrue(r.lastError(), r.lastError().contains("already found by id"));
    }

    /** A carrier that never synced with our server cannot hold our own bundle: no by-id requests. */
    @Test(timeout = 60_000)
    public void untrustedCarrierSkipsProbe() throws Exception {
        carrier.recency = GetRecencyBlobResponse.getDefaultInstance();
        BundleFixtures.bundleJar(carrier.toClient, "own-1", "own-1", 120);
        control.ownership.put("own-1", ControlChannel.Ownership.MINE_NEW);
        control.probeIds.add("own-1");

        VisitResult r = visit();

        assertFalse(r.carrierTrusted());
        assertEquals(0, carrier.byIdDownloads.get());
        assertFalse(r.ownByProbe());
        assertEquals(List.of("own-1"), control.acceptedIds); // still found by download-all
    }
}
