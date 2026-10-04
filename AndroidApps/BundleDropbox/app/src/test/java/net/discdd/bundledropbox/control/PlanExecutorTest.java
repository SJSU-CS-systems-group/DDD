package net.discdd.bundledropbox.control;

import net.discdd.bundledropbox.store.BundleRecord;
import net.discdd.bundledropbox.store.Direction;
import net.discdd.bundledropbox.store.FileRelayStore;
import net.discdd.bundledropbox.store.ServerPlanRetentionPolicy;
import net.discdd.dropbox.control.DropboxControl;
import net.discdd.dropbox.control.DropboxControl.Reason;
import net.discdd.dropbox.control.DropboxControl.SkipReason;
import net.discdd.dropbox.control.InventoryReport;
import net.discdd.dropbox.control.RetentionPlan;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * JVM tests for {@link PlanExecutor} and the {@link ControlStateStore} bookkeeping it drives: monotonic and
 * persisted {@code planSeq}, idempotent replays, exact per-direction deletes, skip reasons, and results queued
 * for the next inventory (Modules/Control Contract, 06 Storage Management Design "PlanExecutor").
 */
public class PlanExecutorTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final AtomicInteger afterDeletions = new AtomicInteger();
    private FileRelayStore store;
    private Path stateFile;
    private ControlStateStore state;
    private PlanExecutor executor;

    @Before
    public void setUp() throws IOException {
        store = ControlFixtures.newStore(tmp.newFolder("dropbox").toPath(), clock);
        stateFile = tmp.getRoot().toPath().resolve("control").resolve(ControlStateStore.FILE_NAME);
        state = new ControlStateStore(stateFile);
        executor = newExecutor(state);
    }

    private PlanExecutor newExecutor(ControlStateStore s) {
        return new PlanExecutor(store, ServerPlanRetentionPolicy.INSTANCE, s, afterDeletions::incrementAndGet,
                                () -> 42_000L);
    }

    private static RetentionPlan plan(long seq, long basedOn, List<RetentionPlan.Deletion> up,
                                      List<RetentionPlan.Deletion> down) {
        return new RetentionPlan(seq, basedOn, null, up, down, null);
    }

    private static RetentionPlan.Deletion del(String id, Reason r) {
        return new RetentionPlan.Deletion(id, r);
    }

    private List<String> ids(Direction d) {
        return store.list(d).stream().map(BundleRecord::bundleId).sorted().collect(Collectors.toList());
    }

    private static List<String> skippedIds(InventoryReport.PlanResult r, SkipReason why) {
        List<String> out = new ArrayList<>();
        for (InventoryReport.SkippedEntry e : r.getSkipped()) if (e.getReason() == why) out.add(e.getBundleId());
        return out;
    }

    @Test
    public void deletesExactlyTheListedIdsInTheNamedDirection() throws IOException {
        ControlFixtures.commit(store, Direction.UPSTREAM, "u1", 100, null);
        ControlFixtures.commit(store, Direction.UPSTREAM, "u2", 100, null);
        ControlFixtures.commit(store, Direction.DOWNSTREAM, "d1", 100, null);
        long d2Size = ControlFixtures.commit(store, Direction.DOWNSTREAM, "d2", 300, null).sizeBytes();
        long u1Size = store.list(Direction.UPSTREAM).get(0).sizeBytes();

        PlanExecutor.Outcome o = executor.apply(plan(1, 1,
                List.of(del("u1", Reason.RECEIVED_BY_SERVER), del("d1", Reason.SUPERSEDED)), // d1 is DOWNSTREAM
                List.of(del("d2", Reason.CLIENT_ACKED), del("gone", Reason.EXPIRED))));

        assertTrue(o.applied());
        assertEquals(List.of("u2"), ids(Direction.UPSTREAM));
        assertEquals(List.of("d1"), ids(Direction.DOWNSTREAM)); // wrong direction: kept
        assertEquals(1, o.deletedUpstream());
        assertEquals(1, o.deletedDownstream());
        assertEquals(2, o.skipped());
        assertEquals(List.of("u1", "d2"), o.result().getDeleted());
        assertEquals(List.of("d1", "gone"), skippedIds(o.result(), SkipReason.NOT_FOUND));
        assertEquals(1, afterDeletions.get());

        ControlStateStore.Snapshot s = state.snapshot();
        assertEquals(1, s.lastAppliedPlanSeq());
        assertEquals(42_000L, s.lastPlanAtMillis());
        assertEquals(1, s.deletedUpstreamCount());
        assertEquals(u1Size, s.deletedUpstreamBytes());
        assertEquals(1, s.deletedDownstreamCount());
        assertEquals(d2Size, s.deletedDownstreamBytes());
        assertEquals(List.of(o.result()), s.pendingResults());
    }

    @Test
    public void replayedAndOlderPlansAreIgnored() throws IOException {
        ControlFixtures.commit(store, Direction.UPSTREAM, "u1", 10, null);
        ControlFixtures.commit(store, Direction.UPSTREAM, "u2", 10, null);
        executor.apply(plan(3, 1, List.of(del("u1", Reason.RECEIVED_BY_SERVER)), List.of()));

        PlanExecutor.Outcome replay = executor.apply(plan(3, 1, List.of(del("u2", Reason.SUPERSEDED)), List.of()));
        PlanExecutor.Outcome older = executor.apply(plan(2, 1, List.of(del("u2", Reason.SUPERSEDED)), List.of()));

        assertFalse(replay.applied());
        assertFalse(older.applied());
        assertNull(replay.result());
        assertEquals(List.of("u2"), ids(Direction.UPSTREAM));
        assertEquals(3, state.snapshot().lastAppliedPlanSeq());
        assertEquals(1, state.snapshot().pendingResults().size());
        assertEquals(1, afterDeletions.get());
    }

    @Test
    public void lastAppliedPlanSeqSurvivesARestart() throws IOException {
        ControlFixtures.commit(store, Direction.UPSTREAM, "u1", 10, null);
        executor.apply(plan(7, 2, List.of(), List.of()));

        ControlStateStore reloaded = new ControlStateStore(stateFile);
        assertEquals(7, reloaded.snapshot().lastAppliedPlanSeq());
        PlanExecutor afterRestart = newExecutor(reloaded);
        assertFalse(afterRestart.apply(plan(7, 2, List.of(del("u1", Reason.EXPIRED)), List.of())).applied());
        assertEquals(List.of("u1"), ids(Direction.UPSTREAM));
        assertTrue(afterRestart.apply(plan(9, 3, List.of(del("u1", Reason.EXPIRED)), List.of())).applied());
        assertEquals(List.of(), ids(Direction.UPSTREAM));
    }

    @Test
    public void aBundleDeletedBeforeACrashIsReportedNotFoundOnReapply() throws IOException {
        // Deletion happens before the state is saved: a crash in between re-applies the plan at the next start.
        ControlFixtures.commit(store, Direction.DOWNSTREAM, "d1", 10, null);
        store.delete(Direction.DOWNSTREAM, "d1"); // the "first attempt" deleted it, then the process died
        PlanExecutor.Outcome o = executor.apply(plan(1, 0, List.of(), List.of(del("d1", Reason.CLIENT_ACKED))));
        assertTrue(o.applied());
        assertEquals(List.of("d1"), skippedIds(o.result(), SkipReason.NOT_FOUND));
        assertEquals(0, afterDeletions.get());
    }

    @Test
    public void unknownReasonStillDeletesAndDuplicatesActOnce() throws IOException {
        ControlFixtures.commit(store, Direction.UPSTREAM, "u1", 10, null);
        PlanExecutor.Outcome o = executor.apply(plan(1, 0, List.of(del("u1", null), del("u1", Reason.SUPERSEDED)),
                                                     List.of()));
        assertEquals(List.of("u1"), o.result().getDeleted());
        assertEquals(0, o.skipped());
        assertEquals(List.of(), ids(Direction.UPSTREAM));
    }

    @Test
    public void policyRefusalIsReportedAsDeleteFailedAndKept() throws IOException {
        ControlFixtures.commit(store, Direction.UPSTREAM, "u1", 10, null);
        PlanExecutor refusing = new PlanExecutor(store, (d, id, r) -> false, state, afterDeletions::incrementAndGet,
                                                 () -> 1L);
        PlanExecutor.Outcome o = refusing.apply(plan(1, 0, List.of(del("u1", Reason.EXPIRED)), List.of()));
        assertEquals(List.of("u1"), skippedIds(o.result(), SkipReason.DELETE_FAILED));
        assertEquals(List.of("u1"), ids(Direction.UPSTREAM));
    }

    @Test
    public void resultsAreForgottenOnceAPlanIsBasedOnAReportThatCarriedThem() throws IOException {
        executor.apply(plan(1, 0, List.of(), List.of()));
        state.recordReportQueued(5, "fp", 1); // report #5 carries plan 1's result
        executor.apply(plan(2, 4, List.of(), List.of())); // based on #4: the server has not seen #5 yet
        assertEquals(List.of(1L, 2L), planSeqs(state.snapshot()));

        state.recordReportQueued(6, "fp2", 2); // carries both
        executor.apply(plan(3, 5, List.of(), List.of())); // the server read #5 (plan 1's result)
        assertEquals(List.of(2L, 3L), planSeqs(state.snapshot()));
        executor.apply(plan(4, 6, List.of(), List.of()));
        assertEquals(List.of(3L, 4L), planSeqs(state.snapshot())); // plan 3's result has not been reported yet
    }

    @Test
    public void pendingResultsAreBoundedByTheContract() throws IOException {
        for (int i = 1; i <= DropboxControl.MAX_PLAN_RESULTS + 5; i++) executor.apply(plan(i, 0, List.of(), List.of()));
        List<Long> seqs = planSeqs(state.snapshot());
        assertEquals(DropboxControl.MAX_PLAN_RESULTS, seqs.size());
        assertEquals(Long.valueOf(DropboxControl.MAX_PLAN_RESULTS + 5), seqs.get(seqs.size() - 1)); // newest kept
    }

    @Test
    public void aPlanBasedOnAHigherReportThanWeRememberRaisesTheReportSeq() throws IOException {
        executor.apply(plan(1, 40, List.of(), List.of()));
        assertEquals(40, state.snapshot().lastReportSeq()); // the next report will be #41, not ignored as stale
    }

    @Test
    public void unreadableStateFileStartsFromZero() throws IOException {
        Files.createDirectories(stateFile.getParent());
        Files.write(stateFile, "{not json".getBytes(StandardCharsets.UTF_8));
        ControlStateStore s = new ControlStateStore(stateFile);
        assertEquals(0, s.snapshot().lastAppliedPlanSeq());
        assertEquals(0, s.snapshot().lastReportSeq());
        assertTrue(s.snapshot().pendingResults().isEmpty());
    }

    private static List<Long> planSeqs(ControlStateStore.Snapshot s) {
        return s.pendingResults().stream().map(InventoryReport.PlanResult::getPlanSeq).collect(Collectors.toList());
    }
}
