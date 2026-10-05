package net.discdd.bundledropbox.control;

import net.discdd.bundledropbox.store.BundleRecord;
import net.discdd.bundledropbox.store.Direction;
import net.discdd.bundledropbox.store.FileDeliveryLog;
import net.discdd.bundledropbox.store.FileRelayStore;
import net.discdd.bundledropbox.store.RelayStore;
import net.discdd.bundledropbox.store.Staged;
import net.discdd.bundledropbox.store.StoreStats;
import net.discdd.dropbox.control.BundleJarInfo;
import net.discdd.dropbox.control.DropboxControl;
import net.discdd.dropbox.control.DropboxControlCodec;
import net.discdd.dropbox.control.InventoryReport;
import net.discdd.dropbox.control.RetentionPlan;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECPublicKey;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * JVM tests for {@link InventoryBuilder}: a full snapshot of the relay store with sizes, the contract's client key
 * fingerprints, delivery-log served counts, the control state's sequence numbers and pending plan results, a
 * fingerprint that ignores {@code reportSeq}/{@code createdAt}, and trimming to the contract bounds.
 */
public class InventoryBuilderTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<FileDeliveryLog.Served> served = new CopyOnWriteArrayList<>();
    private FileRelayStore store;
    private ControlStateStore state;
    private InventoryBuilder builder;

    @Before
    public void setUp() throws IOException {
        store = ControlFixtures.newStore(tmp.newFolder("dropbox").toPath(), clock);
        state = new ControlStateStore(tmp.getRoot().toPath().resolve("state.json"));
        builder = new InventoryBuilder(store, () -> served);
    }

    @Test
    public void reportsEveryHeldBundleWithFingerprintsAndServedCounts() throws Exception {
        ECPublicKey alice = Curve.generateKeyPair().getPublicKey();
        ECPublicKey bob = Curve.generateKeyPair().getPublicKey();
        BundleRecord u1 = ControlFixtures.commit(store, Direction.UPSTREAM, "u1", 100, alice);
        BundleRecord u2 = ControlFixtures.commit(store, Direction.UPSTREAM, "u2", 50, null); // no clientBase.pub
        BundleRecord d1 = ControlFixtures.commit(store, Direction.DOWNSTREAM, "d1", 300, bob);
        ControlFixtures.commit(store, Direction.DOWNSTREAM, "d2", 10, bob);
        served.add(new FileDeliveryLog.Served("d1", 5_000));
        served.add(new FileDeliveryLog.Served("d1", 7_000));
        served.add(new FileDeliveryLog.Served("d1", 6_000));
        served.add(new FileDeliveryLog.Served("deleted-long-ago", 1)); // not held: not reported

        InventoryReport r = builder.build(state.snapshot(), 123_456L).report();

        assertEquals(1, r.getReportSeq());
        assertEquals(0, r.getLastAppliedPlanSeq());
        assertEquals(Long.valueOf(123_456L), r.getCreatedAt());
        assertEquals(ControlFixtures.CAPACITY, r.getCapacityBytes());
        assertEquals(u1.sizeBytes() + u2.sizeBytes() + d1.sizeBytes() + store.list(Direction.DOWNSTREAM).get(1).sizeBytes(),
                     r.getUsedBytes());

        assertEquals(2, r.getUpstream().size());
        InventoryReport.UpstreamEntry e1 = r.getUpstream().get(0);
        assertEquals("u1", e1.getBundleId());
        assertEquals(u1.sizeBytes(), e1.getSizeBytes());
        assertEquals(BundleJarInfo.clientKeyFingerprint(alice.serialize()), e1.getClientKeyB64());
        assertNull(r.getUpstream().get(1).getClientKeyB64());

        InventoryReport.DownstreamEntry de1 = r.getDownstream().get(0);
        assertEquals("d1", de1.getBundleId());
        assertEquals(3, de1.getServedCount());
        assertEquals(Long.valueOf(7_000), de1.getLastServedAt());
        assertEquals(BundleJarInfo.clientKeyFingerprint(bob.serialize()), de1.getClientKeyB64());
        InventoryReport.DownstreamEntry de2 = r.getDownstream().get(1);
        assertEquals(0, de2.getServedCount());
        assertNull(de2.getLastServedAt());
        assertTrue(r.getPlanResults().isEmpty());
    }

    @Test
    public void encodedDraftDecodesToTheSameReport() throws Exception {
        ControlFixtures.commit(store, Direction.UPSTREAM, "u1", 100, Curve.generateKeyPair().getPublicKey());
        InventoryBuilder.Draft d = builder.build(state.snapshot(), 1L);
        assertEquals(d.report(), DropboxControlCodec.decodeInventory(d.encoded()));
    }

    @Test
    public void sequenceNumbersAndPendingResultsComeFromTheControlState() throws Exception {
        ControlFixtures.commit(store, Direction.UPSTREAM, "u1", 10, null);
        PlanExecutor ex = new PlanExecutor(store, state, () -> {}, () -> 9L);
        state.recordReportQueued(4, "x", 1, state.snapshot().pendingResults());
        ex.apply(new RetentionPlan(2, 4, null, List.of(new RetentionPlan.Deletion("u1", null)), List.of(), null));

        InventoryReport r = builder.build(state.snapshot(), 1L).report();

        assertEquals(5, r.getReportSeq());
        assertEquals(2, r.getLastAppliedPlanSeq());
        assertEquals(1, r.getPlanResults().size());
        assertEquals(List.of("u1"), r.getPlanResults().get(0).getDeleted());
        assertTrue(r.getUpstream().isEmpty());
    }

    @Test
    public void fingerprintIgnoresSeqAndTimeButNotContent() throws Exception {
        ControlFixtures.commit(store, Direction.UPSTREAM, "u1", 10, null);
        InventoryBuilder.Draft a = builder.build(state.snapshot(), 1L);
        state.recordReportQueued(a.report().getReportSeq(), a.fingerprint(), 1L, a.report().getPlanResults());
        InventoryBuilder.Draft b = builder.build(state.snapshot(), 99L);
        assertEquals(2, b.report().getReportSeq());
        assertEquals(a.fingerprint(), b.fingerprint());

        served.add(new FileDeliveryLog.Served("u1", 3)); // an UPSTREAM id: not a pickup, changes nothing
        assertEquals(a.fingerprint(), builder.build(state.snapshot(), 5L).fingerprint());

        ControlFixtures.commit(store, Direction.DOWNSTREAM, "d1", 10, null);
        assertNotEquals(a.fingerprint(), builder.build(state.snapshot(), 5L).fingerprint());
    }

    @Test
    public void fingerprintIgnoresAppliedPlansSoAnEmptyPlanDoesNotTriggerAReport() throws Exception {
        ControlFixtures.commit(store, Direction.UPSTREAM, "u1", 10, null);
        InventoryBuilder.Draft a = builder.build(state.snapshot(), 1L);
        state.recordReportQueued(a.report().getReportSeq(), a.fingerprint(), 1L, a.report().getPlanResults());
        PlanExecutor ex = new PlanExecutor(store, state, () -> {}, () -> 9L);

        ex.apply(new RetentionPlan(1, 1, null, List.of(), List.of(), null));
        InventoryBuilder.Draft b = builder.build(state.snapshot(), 2L);
        assertEquals(1, b.report().getLastAppliedPlanSeq());
        assertEquals(1, b.report().getPlanResults().size()); // carried when a report goes anyway
        assertEquals(a.fingerprint(), b.fingerprint());
        assertFalse(state.snapshot().hasUnreportedResults());

        ex.apply(new RetentionPlan(2, 1, null, List.of(new RetentionPlan.Deletion("gone", null)), List.of(), null));
        assertTrue(state.snapshot().hasUnreportedResults()); // a NOT_FOUND outcome is worth a report
        state.recordReportQueued(2, builder.build(state.snapshot(), 3L).fingerprint(), 3L, state.snapshot().pendingResults());
        assertFalse(state.snapshot().hasUnreportedResults());
    }

    @Test
    public void oversizedInventoryIsTrimmedToTheContractBound() throws Exception {
        int n = DropboxControl.MAX_ENTRIES_PER_LIST + 7;
        List<BundleRecord> many = new ArrayList<>(n);
        for (int i = 0; i < n; i++) many.add(new BundleRecord("u" + i, Direction.UPSTREAM, 1, i));
        InventoryBuilder big = new InventoryBuilder(new ListOnlyStore(many), List::of);

        InventoryBuilder.Draft d = big.build(state.snapshot(), 1L);

        assertTrue(d.report().getUpstream().size() <= DropboxControl.MAX_ENTRIES_PER_LIST);
        assertEquals("u0", d.report().getUpstream().get(0).getBundleId()); // oldest first
        assertTrue(d.encoded().length <= DropboxControl.MAX_MESSAGE_BYTES);
    }

    @Test
    public void acknowledgedPageReachesTailPastPermanentlyKeptPrefix() throws Exception {
        int n = DropboxControl.MAX_ENTRIES_PER_LIST + 7;
        List<BundleRecord> many = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            many.add(new BundleRecord(String.format("u%05d", i), Direction.UPSTREAM, 1, i));
        }
        InventoryBuilder big = new InventoryBuilder(new ListOnlyStore(many), List::of);

        InventoryBuilder.Draft first = big.build(state.snapshot(), 1L);
        assertEquals("u00000", first.report().getUpstream().get(0).getBundleId());
        assertEquals("u19999", first.report().getUpstream().get(19_999).getBundleId());
        state.recordInventoryQueued(first, 1L);
        state.recordPlanApplied(1, first.report().getReportSeq(),
                                new InventoryReport.PlanResult(1, List.of(), List.of()),
                                0, 0, 0, 0, 2L);

        InventoryBuilder.Draft second = big.build(state.snapshot(), 3L);
        assertEquals("u20000", second.report().getUpstream().get(0).getBundleId());
        assertEquals("u20006", second.report().getUpstream().get(6).getBundleId());
    }

    @Test
    public void pendingPageKeepsItsBoundaryAcrossRefreshRestartAndStoreChanges() throws Exception {
        int n = DropboxControl.MAX_ENTRIES_PER_LIST + 1;
        List<BundleRecord> many = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            many.add(new BundleRecord(String.format("u%05d", i), Direction.UPSTREAM, 1, i));
        }
        InventoryBuilder big = new InventoryBuilder(new ListOnlyStore(many), List::of);
        InventoryBuilder.Draft first = big.build(state.snapshot(), 1);
        state.recordInventoryQueued(first, 1);
        many.remove(100);
        many.add(new BundleRecord("u19999a", Direction.UPSTREAM, 1, 99_999));
        state = new ControlStateStore(tmp.getRoot().toPath().resolve("state.json"));

        InventoryBuilder.Draft refreshed = big.build(state.snapshot(), 2);
        assertEquals("u19999", refreshed.lastUpstreamId());
        assertFalse(refreshed.report().getUpstream().stream().anyMatch(e -> e.getBundleId().equals("u19999a")));
        state.recordInventoryQueued(refreshed, 2);
        state.recordPlanApplied(1, 999, new InventoryReport.PlanResult(1, List.of(), List.of()),
                                0, 0, 0, 0, 3);
        assertTrue(state.snapshot().hasPendingPage());
        state.recordPlanApplied(2, first.report().getReportSeq(),
                                new InventoryReport.PlanResult(2, List.of(), List.of()), 0, 0, 0, 0, 4);
        assertEquals("u19999a", big.build(state.snapshot(), 5).report().getUpstream().get(0).getBundleId());
    }

    @Test
    public void pagesCoverBothDirectionsDespiteTheFourMiBMessageBound() throws Exception {
        int n = DropboxControl.MAX_ENTRIES_PER_LIST + 7;
        List<BundleRecord> up = new ArrayList<>(n);
        List<BundleRecord> down = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String suffix = String.format("%05d", i) + "x".repeat(70);
            up.add(new BundleRecord("u" + suffix, Direction.UPSTREAM, 1, i));
            down.add(new BundleRecord("d" + suffix, Direction.DOWNSTREAM, 1, i));
        }
        InventoryBuilder big = new InventoryBuilder(new ListOnlyStore(up, down), List::of);
        Set<String> seenUp = new HashSet<>();
        Set<String> seenDown = new HashSet<>();
        for (int visit = 1; visit <= 8 && (seenUp.size() < n || seenDown.size() < n); visit++) {
            InventoryBuilder.Draft draft = big.build(state.snapshot(), visit);
            assertTrue(draft.encoded().length <= DropboxControl.MAX_MESSAGE_BYTES);
            assertTrue(draft.report().getUpstream().size() <= DropboxControl.MAX_ENTRIES_PER_LIST);
            assertTrue(draft.report().getDownstream().size() <= DropboxControl.MAX_ENTRIES_PER_LIST);
            draft.report().getUpstream().forEach(e -> seenUp.add(e.getBundleId()));
            draft.report().getDownstream().forEach(e -> seenDown.add(e.getBundleId()));
            state.recordInventoryQueued(draft, visit);
            state.recordPlanApplied(visit, draft.report().getReportSeq(),
                                    new InventoryReport.PlanResult(visit, List.of(), List.of()),
                                    0, 0, 0, 0, visit);
        }
        assertEquals(n, seenUp.size());
        assertEquals(n, seenDown.size());
    }

    @Test
    public void resultsOmittedByTheByteBoundAreNotAcknowledgedAsReported() throws Exception {
        List<String> deleted = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) deleted.add("b".repeat(80) + i);
        for (int seq = 1; seq <= 3; seq++) {
            state.recordPlanApplied(seq, 0, new InventoryReport.PlanResult(seq, deleted, List.of()),
                                    0, 0, 0, 0, seq);
        }
        InventoryBuilder.Draft draft = builder.build(state.snapshot(), 10);
        List<Long> omitted = new ArrayList<>(List.of(1L, 2L, 3L));
        for (InventoryReport.PlanResult r : draft.report().getPlanResults()) omitted.remove(r.getPlanSeq());
        assertFalse("Fixture must exercise actual message-size trimming", omitted.isEmpty());
        state.recordReportQueued(draft.report().getReportSeq(), draft.fingerprint(), 10, draft.report().getPlanResults());
        state.recordPlanApplied(4, draft.report().getReportSeq(),
                                new InventoryReport.PlanResult(4, List.of(), List.of()), 0, 0, 0, 0, 11);
        List<Long> remaining = new ArrayList<>();
        for (InventoryReport.PlanResult r : state.snapshot().pendingResults()) remaining.add(r.getPlanSeq());
        assertTrue("A plan based on the trimmed report cannot acknowledge omitted outcomes",
                   remaining.containsAll(omitted));
    }

    /** A relay store that only lists records (for bound tests; no files, so no fingerprints). */
    private static final class ListOnlyStore implements RelayStore {
        private final List<BundleRecord> up;
        private final List<BundleRecord> down;

        ListOnlyStore(List<BundleRecord> up) { this(up, List.of()); }

        ListOnlyStore(List<BundleRecord> up, List<BundleRecord> down) {
            this.up = up;
            this.down = down;
        }

        @Override public Staged beginStaging(Direction d) { throw new UnsupportedOperationException(); }
        @Override public BundleRecord commit(Staged s, String id) { throw new UnsupportedOperationException(); }
        @Override public void abort(Staged s) {}
        @Override public List<BundleRecord> list(Direction d) { return d == Direction.UPSTREAM ? up : down; }
        @Override public Path pathFor(Direction d, String bundleId) { return null; }
        @Override public boolean contains(Direction d, String bundleId) { return false; }
        @Override public boolean touch(Direction d, String bundleId) { return false; }
        @Override public StoreStats stats() { return new StoreStats(up.size(), up.size(), down.size(), down.size(), 0, 1L << 40); }
        @Override public boolean hasCapacityFor(Direction d, long bytes) { return true; }
        @Override public void delete(Direction d, String bundleId) {}
    }
}
