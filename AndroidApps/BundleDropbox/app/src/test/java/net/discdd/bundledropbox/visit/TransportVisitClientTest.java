package net.discdd.bundledropbox.visit;

import net.discdd.bundledropbox.store.BundleRecord;
import net.discdd.bundledropbox.store.Direction;
import net.discdd.bundledropbox.store.DropboxPaths;
import net.discdd.bundledropbox.store.FileRecencyBlobStore;
import net.discdd.bundledropbox.store.FileRelayStore;
import net.discdd.bundledropbox.store.JsonForwardLedger;
import net.discdd.bundledropbox.store.Staged;
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
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end JVM tests of {@link TransportVisitClient} against {@link FakeCarrier}, a localhost copy of the
 * stock transport's gRPC service served over real TLS (OkHttp server + {@code GrpcSecurityKey}), using the
 * production {@link CarrierChannelFactory#tls} channel. No Android runtime and no in-process transport needed.
 */
public class TransportVisitClientTest {
    private static final String CARRIER = "carrier-T1";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FakeCarrier carrier;
    private FakeRelayStore store;
    private FakeLedgerAndRecency.Ledger ledger;
    private FakeLedgerAndRecency.Recency recency;
    private TransportVisitClient client;
    private Path fixtures;

    @Before
    public void setUp() throws Exception {
        carrier = new FakeCarrier(tmp.newFolder("carrier").toPath());
        store = new FakeRelayStore(tmp.newFolder("dropbox").toPath());
        ledger = new FakeLedgerAndRecency.Ledger();
        recency = new FakeLedgerAndRecency.Recency();
        fixtures = tmp.newFolder("fixtures").toPath();
        GrpcSecurityKey dropboxKey = new GrpcSecurityKey(tmp.newFolder("GrpcSecurity").toPath(), SecurityUtils.SERVER);
        client = new TransportVisitClient(store,
                                          ledger,
                                          recency,
                                          CarrierChannelFactory.tls(dropboxKey),
                                          VisitOptions.DEFAULTS.withProbe(3, 50),
                                          System::currentTimeMillis);
    }

    @After
    public void tearDown() throws Exception {
        carrier.close();
    }

    // ------------------------------------------------------------------ helpers

    private VisitResult visit() {
        return visit(() -> true);
    }

    private VisitResult visit(java.util.function.BooleanSupplier keepGoing) {
        return client.visit(InetAddress.getLoopbackAddress(), carrier.port, CARRIER, keepGoing);
    }

    /** Puts a bundle on the carrier exactly as T stores server bundles: file name == bundle id. */
    private Path carrierHolds(String id, int payload) throws IOException {
        return BundleFixtures.bundleJar(carrier.toClient, id, id, payload);
    }

    private BundleRecord dropboxHoldsUpstream(String id, int payload) throws IOException {
        return store.seed(Direction.UPSTREAM, BundleFixtures.bundleJar(fixtures, id, id, payload));
    }

    private static GetRecencyBlobResponse successBlob(long ts) {
        return GetRecencyBlobResponse.newBuilder()
                .setStatus(RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS)
                .setRecencyBlob(RecencyBlob.newBuilder().setBlobTimestamp(ts).setSenderId("sha1-of-T"))
                .build();
    }

    private List<String> downstreamIds() {
        return store.list(Direction.DOWNSTREAM).stream().map(BundleRecord::bundleId).sorted().collect(Collectors.toList());
    }

    private void assertSameBytes(Path expected, Path actual) throws IOException {
        assertArrayEquals(expected.toString(), Files.readAllBytes(expected), Files.readAllBytes(actual));
    }

    // ------------------------------------------------------------------ tests

    @Test(timeout = 60_000)
    public void fullVisitUploadsUnforwardedAndDownloadsAllWithDedupe() throws Exception {
        carrier.recency = successBlob(1_000);
        dropboxHoldsUpstream("up1", 300);
        dropboxHoldsUpstream("up2", 500);
        ledger.recordForwarded("up1", CARRIER, 1); // already given to this carrier on an earlier visit
        Path d1 = carrierHolds("d1", 100);
        Path d2 = carrierHolds("d2", 200);
        Path d3 = carrierHolds("d3", 300);
        store.seed(Direction.DOWNSTREAM, d2); // already held: must be downloaded but discarded

        VisitResult r = visit();

        assertTrue(r.toString(), r.ok());
        assertTrue(r.reachable());
        assertTrue(r.recencyUpdated());
        assertEquals(1_000, recency.currentTimestamp());
        assertEquals(1, r.uploaded());
        assertEquals(2, r.downloaded());
        assertEquals(1, r.skipped());
        assertEquals(0, r.errors());
        // upload: only up2, byte-identical under its id; ledger updated after SUCCESS
        assertFalse(Files.exists(carrier.toServer.resolve("up1")));
        assertSameBytes(store.pathFor(Direction.UPSTREAM, "up2"), carrier.toServer.resolve("up2"));
        assertTrue(ledger.wasForwardedTo("up2", CARRIER));
        // download-all: everything held, named from bundle.id, byte-identical
        assertEquals(List.of("d1", "d2", "d3"), downstreamIds());
        assertSameBytes(d1, store.pathFor(Direction.DOWNSTREAM, "d1"));
        assertSameBytes(d3, store.pathFor(Direction.DOWNSTREAM, "d3"));
        // one PSI session per bundle (counting session reused for index 0), single pass
        assertEquals(3, carrier.psiExchanges.get());
        assertEquals(0, store.stagingFileCount());
    }

    @Test(timeout = 60_000)
    public void secondVisitTransfersNothingNew() throws Exception {
        carrier.recency = successBlob(1_000);
        dropboxHoldsUpstream("up1", 100);
        carrierHolds("d1", 100);
        carrierHolds("d2", 100);
        assertTrue(visit().ok());

        VisitResult again = visit();

        assertTrue(again.toString(), again.ok());
        assertFalse("same blob timestamp is not newer", again.recencyUpdated());
        assertEquals(0, again.uploaded());
        assertEquals(0, again.downloaded());
        assertEquals(2, again.skipped());
        assertEquals(1, carrier.uploads.get());
    }

    @Test(timeout = 60_000)
    public void uploadsToADifferentCarrierAgain() throws Exception {
        dropboxHoldsUpstream("up1", 100);
        ledger.recordForwarded("up1", "some-other-carrier", 1);

        VisitResult r = visit();

        assertEquals(1, r.uploaded());
        assertTrue(ledger.wasForwardedTo("up1", CARRIER));
    }

    @Test(timeout = 60_000)
    public void keepGoingStopsBetweenBundlesAndInFlightBundleCompletes() throws Exception {
        dropboxHoldsUpstream("up1", 100);
        dropboxHoldsUpstream("up2", 100);
        Path d1 = carrierHolds("d1", 50_000);
        Path d2 = carrierHolds("d2", 50_000);
        Path d3 = carrierHolds("d3", 50_000);

        // Dwell "expires" as soon as the first downstream bundle has landed.
        VisitResult r = visit(() -> store.list(Direction.DOWNSTREAM).isEmpty());

        assertTrue(r.stoppedEarly());
        assertFalse(r.ok());
        assertEquals("uploads run before downloads", 2, r.uploaded());
        assertEquals(1, r.downloaded());
        assertEquals(0, r.errors());
        String got = store.list(Direction.DOWNSTREAM).get(0).bundleId();
        Path original = got.equals("d1") ? d1 : got.equals("d2") ? d2 : d3;
        assertSameBytes(original, store.pathFor(Direction.DOWNSTREAM, got)); // complete, not truncated
        assertEquals(1, carrier.psiDownloads.get());
        assertEquals(0, store.stagingFileCount());
    }

    @Test(timeout = 60_000)
    public void uploadsNewestFirst() throws Exception {
        // The server ignores counters at or below the last one it saw from a client, so the newest bundle is
        // the valuable one and must go first when the dwell only allows one upload.
        dropboxHoldsUpstream("up-old", 100);
        dropboxHoldsUpstream("up-new", 100);

        VisitResult r = visit(() -> carrier.uploads.get() == 0);

        assertEquals(1, r.uploaded());
        assertTrue(Files.exists(carrier.toServer.resolve("up-new")));
        assertFalse(Files.exists(carrier.toServer.resolve("up-old")));
    }

    @Test(timeout = 60_000)
    public void downloadsResumeWhereThePreviousVisitStopped() throws Exception {
        carrierHolds("d1", 100);
        carrierHolds("d2", 100);
        carrierHolds("d3", 100);

        // Each "dwell" only has time for one new bundle. Without resume, every visit would restart at index 0
        // and spend its time re-downloading bundles the dropbox already holds.
        for (int visitNo = 1; visitNo <= 3; visitNo++) {
            int target = visitNo;
            VisitResult r = visit(() -> store.list(Direction.DOWNSTREAM).size() < target);
            assertEquals("visit " + visitNo, 1, r.downloaded());
            assertEquals("visit " + visitNo + " re-downloaded a held bundle", 0, r.skipped());
        }
        assertEquals(List.of("d1", "d2", "d3"), downstreamIds());
    }

    @Test(timeout = 60_000)
    public void callerCancelSignalStopsVisitEvenIfRaisedBeforeItStarts() throws Exception {
        carrier.recency = successBlob(5_000);
        dropboxHoldsUpstream("up1", 100);
        carrierHolds("d1", 100);

        // The gate token was force-released between tryAdmit and visit(): nothing may run.
        VisitResult r = client.visit(InetAddress.getLoopbackAddress(), carrier.port, CARRIER, () -> true, () -> true);

        assertTrue(r.stoppedEarly());
        assertFalse(r.recencyFetched());
        assertEquals(0, r.uploaded());
        assertEquals(0, carrier.psiExchanges.get());
    }

    @Test(timeout = 60_000)
    public void recencyIsFetchedEvenWhenAlreadyToldToStop() throws Exception {
        carrier.recency = successBlob(5_000);
        dropboxHoldsUpstream("up1", 100);
        carrierHolds("d1", 100);

        VisitResult r = visit(() -> false);

        assertTrue(r.recencyUpdated());
        assertTrue(r.stoppedEarly());
        assertEquals(0, r.uploaded());
        assertEquals(0, r.downloaded());
        assertEquals(0, carrier.psiExchanges.get());
    }

    @Test(timeout = 60_000)
    public void undefinedRecencyBlobIsNotAnError() throws Exception {
        carrier.recency = GetRecencyBlobResponse.getDefaultInstance(); // carrier never synced with the server
        carrierHolds("d1", 100);

        VisitResult r = visit();

        assertTrue(r.toString(), r.ok());
        assertFalse(r.recencyUpdated());
        assertEquals(0, recency.currentTimestamp());
        assertEquals(1, r.downloaded());
    }

    @Test(timeout = 120_000)
    public void largeBundlesAreChunkedBothWays() throws Exception {
        // > 4,000,000 bytes: the upload must be split into 2 chunks to stay under T's 5 MiB inbound limit (one
        // 5 MiB message would be rejected); the download streams through T's 4 MiB transfer buffer.
        int big = 5 * 1024 * 1024;
        dropboxHoldsUpstream("bigUp", big);
        Path bigDown = carrierHolds("bigDown", big);

        VisitResult r = visit();

        assertTrue(r.toString(), r.ok());
        assertSameBytes(store.pathFor(Direction.UPSTREAM, "bigUp"), carrier.toServer.resolve("bigUp"));
        assertSameBytes(bigDown, store.pathFor(Direction.DOWNSTREAM, "bigDown"));
    }

    @Test(timeout = 60_000)
    public void nonBundleFileOnCarrierIsCountedAndSkipped() throws Exception {
        byte[] junk = new byte[1000];
        new Random(1).nextBytes(junk);
        Files.write(carrier.toClient.resolve("junk"), junk);
        carrierHolds("d1", 100);

        VisitResult r = visit();

        assertFalse(r.ok());
        assertEquals(1, r.errors());
        assertEquals(1, r.downloaded());
        assertEquals(List.of("d1"), downstreamIds());
        assertEquals(0, store.stagingFileCount());
    }

    @Test(timeout = 60_000)
    public void listingChangeDuringPassTriggersOneExtraPass() throws Exception {
        carrierHolds("d1", 100);
        carrierHolds("d2", 100);
        AtomicBoolean added = new AtomicBoolean();
        carrier.afterDownload = i -> {
            if (added.compareAndSet(false, true)) {
                try {
                    carrierHolds("dNew", 100); // a T<->S sync landing mid-visit
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        VisitResult r = visit();

        assertTrue(r.toString(), r.ok());
        assertEquals(List.of("d1", "d2", "dNew"), downstreamIds());
        assertEquals(3, r.downloaded());
        assertTrue("second pass re-downloads held bundles", r.skipped() >= 2);
    }

    @Test(timeout = 60_000)
    public void storeFullStopsDownloads() throws Exception {
        carrierHolds("d1", 100);
        store.hasCapacity = false;

        VisitResult r = visit();

        assertFalse(r.ok());
        assertEquals(0, r.downloaded());
        assertEquals(0, carrier.psiDownloads.get());
    }

    @Test(timeout = 60_000)
    public void cancelAbortsTheInFlightDownloadAndKeepsItRetryable() throws Exception {
        carrierHolds("d1", 100);
        carrier.downloadGate = new CountDownLatch(1);

        CompletableFuture<VisitResult> running = CompletableFuture.supplyAsync(this::visit);
        assertTrue(carrier.downloadBlocked.await(20, TimeUnit.SECONDS));
        client.cancel(); // drain cap reached
        VisitResult r = running.get(20, TimeUnit.SECONDS);

        assertTrue(r.toString(), r.stoppedEarly());
        assertFalse(r.ok());
        assertEquals(0, r.downloaded());
        assertTrue(downstreamIds().isEmpty());
        assertEquals(0, store.stagingFileCount());

        // The next visit starts clean and gets the bundle.
        carrier.downloadGate = null;
        VisitResult next = visit();
        assertTrue(next.toString(), next.ok());
        assertEquals(List.of("d1"), downstreamIds());
    }

    @Test(timeout = 30_000)
    public void unreachableCarrierFailsFastWithoutThrowing() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }

        VisitResult r = client.visit(InetAddress.getLoopbackAddress(), closedPort, CARRIER, () -> true);

        assertFalse(r.reachable());
        assertFalse(r.ok());
        assertEquals(1, r.errors());
        assertTrue(r.lastError().contains("not reachable"));
    }

    /** Same round trip against the production store/ implementations instead of the test fakes. */
    @Test(timeout = 60_000)
    public void worksWithRealStoreImplementations() throws Exception {
        DropboxPaths paths = new DropboxPaths(tmp.newFolder("realRoot").toPath());
        FileRelayStore realStore = new FileRelayStore(paths, 64L * 1024 * 1024);
        JsonForwardLedger realLedger = new JsonForwardLedger(paths);
        FileRecencyBlobStore realRecency = new FileRecencyBlobStore(paths);
        Staged up = realStore.beginStaging(Direction.UPSTREAM);
        try (var out = up.open()) {
            Files.copy(BundleFixtures.bundleJar(fixtures, "realUp", "realUp", 1000), out);
        }
        realStore.commit(up, "realUp");
        carrier.recency = successBlob(7_000);
        Path d1 = carrierHolds("d1", 2000);
        TransportVisitClient real = new TransportVisitClient(realStore,
                                                             realLedger,
                                                             realRecency,
                                                             CarrierChannelFactory.tls(null),
                                                             VisitOptions.DEFAULTS.withProbe(3, 50),
                                                             System::currentTimeMillis);

        VisitResult r = real.visit(InetAddress.getLoopbackAddress(), carrier.port, CARRIER, () -> true);

        assertTrue(r.toString(), r.ok());
        assertTrue(r.recencyUpdated());
        assertEquals(7_000, realRecency.currentTimestamp());
        assertTrue(realLedger.wasForwardedTo("realUp", CARRIER));
        assertSameBytes(realStore.pathFor(Direction.UPSTREAM, "realUp"), carrier.toServer.resolve("realUp"));
        assertSameBytes(d1, realStore.pathFor(Direction.DOWNSTREAM, "d1"));
        assertEquals(0, realStore.stats().stagedBytes());
    }

    @Test
    public void readJarBundleIdRejectsNonJarsAndUncleanIds() throws Exception {
        assertEquals("abc-_=1", TransportVisitClient.readJarBundleId(
                BundleFixtures.bundleJar(fixtures, "ok", " abc-_=1\n", 10)));
        assertNull(TransportVisitClient.readJarBundleId(BundleFixtures.bundleJar(fixtures, "bad", "../evil", 10)));
        Path notJar = Files.write(fixtures.resolve("plain"), new byte[] { 1, 2, 3 });
        assertNull(TransportVisitClient.readJarBundleId(notJar));
    }
}
