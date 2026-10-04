package net.discdd.bundledropbox.control;

import net.discdd.bundledropbox.store.BundleRecord;
import net.discdd.bundledropbox.store.Direction;
import net.discdd.bundledropbox.store.RelayStore;
import net.discdd.bundledropbox.store.RetentionPolicy;
import net.discdd.dropbox.control.DropboxControl;
import net.discdd.dropbox.control.InventoryReport;
import net.discdd.dropbox.control.RetentionPlan;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Applies the server's {@link RetentionPlan}s to the dropbox's {@link RelayStore}: the only code that deletes
 * relayed bundles without an operator pressing "clear" (06 Storage Management Design, "PlanExecutor").
 *
 * <p>Rules (Modules/Control Contract):
 * <ul>
 *   <li><b>Monotonic.</b> A plan is applied only if {@code planSeq > lastAppliedPlanSeq}. Older or repeated plans
 *       (a replayed ADU, a bundle re-offered by another carrier) are ignored, so applying is idempotent.</li>
 *   <li><b>Exact.</b> Each listed id is deleted only from the direction the plan names. An id the dropbox does not
 *       hold in that direction is reported as {@code NOT_FOUND}, even if it sits in the other direction; a file
 *       that cannot be deleted is reported as {@code DELETE_FAILED} and kept.</li>
 *   <li><b>Never by absence.</b> Bundles the plan does not list are left alone ({@code keep[]} is informational).</li>
 *   <li><b>Reported.</b> The outcome becomes an {@link InventoryReport.PlanResult} in the next inventories
 *       ({@link ControlStateStore}).</li>
 * </ul>
 * Every reason the server gives is a monotonic fact (Research/R7 §5), so a stale plan cannot delete anything the
 * server still needs. Each deletion is also checked against the {@link RetentionPolicy}.
 *
 * <p>Crash safety: deletions happen before the state is saved. A crash in between re-applies the same plan at the
 * next start (its ADU is only deleted after this returns, {@code DropboxControlEndpoint}); bundles already deleted
 * are then reported as {@code NOT_FOUND}, which the server treats like a deletion.
 *
 * <p>Thread-safe ({@code synchronized}); the relay store is thread-safe itself. Pure Java, JVM-tested.
 */
public final class PlanExecutor {
    private static final Logger logger = Logger.getLogger(PlanExecutor.class.getName());

    /** What {@link #apply} did with a plan. */
    public static final class Outcome {
        private final boolean applied;
        private final long planSeq;
        private final InventoryReport.PlanResult result;
        private final int deletedUpstream;
        private final int deletedDownstream;
        private final int skipped;

        private Outcome(boolean applied, long planSeq, InventoryReport.PlanResult result, int deletedUpstream,
                        int deletedDownstream, int skipped) {
            this.applied = applied;
            this.planSeq = planSeq;
            this.result = result;
            this.deletedUpstream = deletedUpstream;
            this.deletedDownstream = deletedDownstream;
            this.skipped = skipped;
        }

        static Outcome stale(long planSeq) {
            return new Outcome(false, planSeq, null, 0, 0, 0);
        }

        /** @return false if the plan was ignored because it was not newer than the last applied one */
        public boolean applied() { return applied; }

        /** @return the plan's sequence number */
        public long planSeq() { return planSeq; }

        /** @return the result reported to the server, or {@code null} for an ignored plan */
        public InventoryReport.PlanResult result() { return result; }

        /** @return UPSTREAM bundles deleted */
        public int deletedUpstream() { return deletedUpstream; }

        /** @return DOWNSTREAM bundles deleted */
        public int deletedDownstream() { return deletedDownstream; }

        /** @return listed bundles not deleted (not found or delete failed) */
        public int skipped() { return skipped; }

        @Override
        public String toString() {
            return applied ? "plan #" + planSeq + ": deleted " + deletedUpstream + " up, " + deletedDownstream +
                    " down, skipped " + skipped : "plan #" + planSeq + " ignored (not newer than the last applied)";
        }
    }

    private final RelayStore store;
    private final RetentionPolicy policy;
    private final ControlStateStore state;
    private final Runnable afterDeletions;
    private final LongSupplier wallClock;

    /**
     * @param store          the relay store bundles are deleted from
     * @param policy         asked before each deletion ({@code ServerPlanRetentionPolicy} in production)
     * @param state          persisted plan sequence, results and totals
     * @param afterDeletions run once after a plan deleted something (the service prunes the forward ledger)
     * @param wallClock      epoch millis
     */
    public PlanExecutor(RelayStore store, RetentionPolicy policy, ControlStateStore state, Runnable afterDeletions,
                        LongSupplier wallClock) {
        this.store = Objects.requireNonNull(store, "store");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.state = Objects.requireNonNull(state, "state");
        this.afterDeletions = Objects.requireNonNull(afterDeletions, "afterDeletions");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
    }

    /**
     * Applies {@code plan} if it is newer than the last applied plan.
     *
     * @param plan a decoded plan
     * @return what happened
     * @throws IOException if the state could not be saved; the caller keeps the plan's ADU so it is retried
     */
    public synchronized Outcome apply(RetentionPlan plan) throws IOException {
        Objects.requireNonNull(plan, "plan");
        long last = state.snapshot().lastAppliedPlanSeq();
        if (plan.getPlanSeq() <= last) {
            logger.log(Level.INFO, "Ignoring plan #" + plan.getPlanSeq() + ": already applied #" + last);
            return Outcome.stale(plan.getPlanSeq());
        }
        List<String> deleted = new ArrayList<>();
        List<InventoryReport.SkippedEntry> skipped = new ArrayList<>();
        long[] up = deleteAll(Direction.UPSTREAM, plan.getDeleteUpstream(), deleted, skipped);
        long[] down = deleteAll(Direction.DOWNSTREAM, plan.getDeleteDownstream(), deleted, skipped);
        if (up[0] + down[0] > 0) {
            try {
                afterDeletions.run();
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Post-deletion hook failed", e);
            }
        }
        InventoryReport.PlanResult result = new InventoryReport.PlanResult(plan.getPlanSeq(),
                                                                           cap(deleted),
                                                                           cap(skipped));
        state.recordPlanApplied(plan.getPlanSeq(), plan.getBasedOnReportSeq(), result, (int) up[0], up[1],
                                (int) down[0], down[1], wallClock.getAsLong());
        Outcome outcome = new Outcome(true, plan.getPlanSeq(), result, (int) up[0], (int) down[0], skipped.size());
        logger.log(Level.INFO, "Applied " + outcome);
        return outcome;
    }

    /** @return {count, bytes} deleted */
    private long[] deleteAll(Direction d, List<RetentionPlan.Deletion> orders, List<String> deleted,
                             List<InventoryReport.SkippedEntry> skipped) {
        long count = 0;
        long bytes = 0;
        if (orders.isEmpty()) return new long[]{0, 0};
        Map<String, BundleRecord> held = new HashMap<>();
        for (BundleRecord r : store.list(d)) held.put(r.bundleId(), r);
        Map<String, DropboxControl.Reason> unique = new HashMap<>();
        Set<String> order = new LinkedHashSet<>(); // a plan may repeat an id; act on it once, in plan order
        for (RetentionPlan.Deletion o : orders) {
            if (order.add(o.getBundleId())) unique.put(o.getBundleId(), o.getReason());
        }
        for (String id : order) {
            BundleRecord r = held.get(id);
            if (r == null || !store.contains(d, id)) {
                skipped.add(new InventoryReport.SkippedEntry(id, DropboxControl.SkipReason.NOT_FOUND));
                continue;
            }
            if (!policy.mayDelete(d, id, unique.get(id))) {
                // Kept on purpose; reported as a failed delete so the server keeps tracking it.
                skipped.add(new InventoryReport.SkippedEntry(id, DropboxControl.SkipReason.DELETE_FAILED));
                continue;
            }
            store.delete(d, id);
            if (store.contains(d, id)) {
                skipped.add(new InventoryReport.SkippedEntry(id, DropboxControl.SkipReason.DELETE_FAILED));
            } else {
                deleted.add(id);
                count++;
                bytes += r.sizeBytes();
            }
        }
        return new long[]{count, bytes};
    }

    /** Keeps a result list within the contract's per-list bound; the counts in the log stay exact. */
    private static <T> List<T> cap(List<T> list) {
        return list.size() <= DropboxControl.MAX_ENTRIES_PER_LIST ? list :
               new ArrayList<>(list.subList(0, DropboxControl.MAX_ENTRIES_PER_LIST));
    }
}
