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
 *   <li>downloaded bundles addressed to the dropbox go to {@link ControlChannel#accept} with the carrier's
 *       recency-blob sender id and <b>never</b> into the relay store;</li>
 *   <li>after a processed plan the regenerated own bundle (carrying the ack) is uploaded at the end;</li>
 *   <li>a failing control channel never breaks the relay.</li>
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
    private TransportVisitClient client;
    private Path fixtures;

    /** Scripted control channel: queued own bundles, a fixed ownership table, recorded accepts. */
    private static final class FakeControl implements ControlChannel {
        final Deque<OwnBundle> ownBundles = new ArrayDeque<>();
        OwnBundle lastOwn;
        final Map<String, Ownership> ownership = new HashMap<>();
        final List<String> acceptedIds = new ArrayList<>();
        final List<String> acceptedSenders = new ArrayList<>();
        final List<byte[]> acceptedBytes = new ArrayList<>();
        int ownBundleCalls;
        boolean explode;

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
        public synchronized boolean accept(Path stagedJar, String carrierSenderId) {
            try {
                acceptedBytes.add(Files.readAllBytes(stagedJar));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            acceptedIds.add(TransportVisitClient.readJarBundleId(stagedJar));
            acceptedSenders.add(carrierSenderId);
            return true;
        }
    }

    @Before
    public void setUp() throws Exception {
        carrier = new FakeCarrier(tmp.newFolder("carrier").toPath());
        store = new FakeRelayStore(tmp.newFolder("dropbox").toPath());
        ledger = new FakeLedgerAndRecency.Ledger();
        control = new FakeControl();
        fixtures = tmp.newFolder("fixtures").toPath();
        GrpcSecurityKey dropboxKey = new GrpcSecurityKey(tmp.newFolder("GrpcSecurity").toPath(), SecurityUtils.SERVER);
        client = new TransportVisitClient(store,
                                          ledger,
                                          new FakeLedgerAndRecency.Recency(),
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
        assertEquals(List.of(SENDER_ID), control.acceptedSenders);
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

    @Test(timeout = 60_000)
    public void untrustedRecencyBlobGivesNoSenderId() throws Exception {
        carrier.recency = GetRecencyBlobResponse.getDefaultInstance(); // carrier never synced
        BundleFixtures.bundleJar(carrier.toClient, "mine1", "mine1", 120);
        control.ownership.put("mine1", ControlChannel.Ownership.MINE_NEW);

        visit();

        assertEquals(List.of("mine1"), control.acceptedIds);
        assertEquals(java.util.Collections.singletonList(null), control.acceptedSenders);
    }
}
