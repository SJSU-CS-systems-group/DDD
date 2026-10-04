package net.discdd.bundledropbox.control;

import net.discdd.bundledropbox.status.ControlStatus;
import net.discdd.bundledropbox.store.BundleRecord;
import net.discdd.bundledropbox.store.Direction;
import net.discdd.bundledropbox.store.FileDeliveryLog;
import net.discdd.bundledropbox.store.RelayStore;
import net.discdd.bundledropbox.visit.ControlChannel;
import net.discdd.dropbox.control.RetentionPlan;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the dropbox's control channel and is the {@link ControlChannel} every carrier visit uses: the
 * {@link DropboxControlEndpoint} (replaced when the operator imports other server keys), the
 * {@link ControlStateStore} kept next to it, the {@link InventoryBuilder} and the {@link PlanExecutor}.
 *
 * <p>Flow per visit (06 Storage Management Design "Visit flow"):
 * <ol>
 *   <li>{@link #ownBundleForVisit()}: build an inventory; queue it only if the held bundles differ from the last
 *       one queued, a plan deleted or skipped something that no report carried yet, or the last report is older
 *       than {@link #REPORT_REFRESH_MS} (so the server can re-evaluate time-based rules such as its unknown-bundle
 *       TTL); return the endpoint's newest bundle. Applying an empty plan alone never queues a report, which
 *       keeps the report/plan exchange from running forever ({@link InventoryBuilder#fingerprint}).</li>
 *   <li>{@link #classify} / {@link #accept}: bundles addressed to the dropbox are processed by the endpoint, whose
 *       plans reach {@link PlanExecutor#apply} through the endpoint's sink.</li>
 * </ol>
 *
 * <p>Failure isolation: if the endpoint cannot be opened (missing or bad keys, I/O), the manager reports the
 * problem in its {@link ControlStatus} and behaves like {@link ControlChannel#NONE}; the relay keeps working
 * exactly as in v1. Nothing here throws into the visit.
 *
 * <p>Threading: one lock (this object) serializes every control operation, which also serializes all
 * bundle-core calls (R7 §1: none of them are thread-safe). Status is published as an immutable snapshot that the
 * UI reads without the lock.
 */
public final class DropboxControlManager implements ControlChannel {
    private static final Logger logger = Logger.getLogger(DropboxControlManager.class.getName());

    /** Re-send an unchanged inventory after this long, so time-based server rules get re-evaluated (6 h). */
    public static final long REPORT_REFRESH_MS = 6L * 60 * 60 * 1000;

    /** Service-side reactions (logs, status). Called with the manager's lock held: must not block. */
    public interface Listener {
        /** A plan was applied (or ignored as stale). */
        void planApplied(PlanExecutor.Outcome outcome);

        /** A new inventory was queued for upload. */
        void reportQueued(long reportSeq, int upstreamEntries, int downstreamEntries);

        /** Something the operator should know (the endpoint failed to open, a visit could not use it). */
        void problem(String message);

        /** {@link #status()} changed. */
        void statusChanged();
    }

    private final Path root;
    private final RelayStore store;
    private final Runnable afterDeletions;
    private final LongSupplier wallClock;
    private final Listener listener;
    private final InventoryBuilder inventory;

    private DropboxControlEndpoint endpoint; // guarded by this; null while unavailable
    private ControlStateStore state; // guarded by this
    private PlanExecutor executor; // guarded by this
    private String serverKeysLabel = ""; // guarded by this
    private String problem; // guarded by this
    private volatile ControlStatus status = ControlStatus.NONE;

    /**
     * @param root           endpoint root ({@code getDataDir()/ControlEndpoint})
     * @param store          the relay store (inventory source, deletion target)
     * @param servedEvents   delivery-log events ({@code FileDeliveryLog::readAll})
     * @param afterDeletions run after a plan deleted bundles (prune the forward ledger)
     * @param wallClock      epoch millis
     * @param listener       service reactions
     */
    public DropboxControlManager(Path root, RelayStore store, Supplier<List<FileDeliveryLog.Served>> servedEvents,
                                 Runnable afterDeletions, LongSupplier wallClock, Listener listener) {
        this.root = Objects.requireNonNull(root, "root");
        this.store = Objects.requireNonNull(store, "store");
        this.afterDeletions = Objects.requireNonNull(afterDeletions, "afterDeletions");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.inventory = new InventoryBuilder(store, servedEvents);
    }

    /**
     * Opens the endpoint for the given server keys, replacing any open one (keys changed). Replays plans that
     * were stored but not applied before the last shutdown.
     *
     * @param keysLabel    operator-facing description of the keys (e.g. "built-in", "imported (host:port)")
     * @param identityPem  server identity key file text
     * @param signedPrePem server signed pre-key file text
     * @param ratchetPem   server ratchet key file text
     * @return true if the control channel is available
     */
    public synchronized boolean open(String keysLabel, byte[] identityPem, byte[] signedPrePem, byte[] ratchetPem) {
        if (endpoint != null) endpoint.close();
        endpoint = null;
        executor = null;
        state = null;
        serverKeysLabel = keysLabel == null ? "" : keysLabel;
        try {
            DropboxControlEndpoint opened = DropboxControlEndpoint.open(root, identityPem, signedPrePem, ratchetPem,
                                                                        this::applyPlan);
            state = new ControlStateStore(root.resolve(DropboxControlEndpoint.CONTROL_DIR_NAME)
                                                  .resolve(ControlStateStore.FILE_NAME));
            executor = new PlanExecutor(store, state, afterDeletions, wallClock);
            endpoint = opened;
            problem = null;
            opened.replayPending();
        } catch (Throwable e) {
            logger.log(Level.SEVERE, "Control endpoint unavailable; relaying without server-managed storage", e);
            endpoint = null;
            problem = "Control endpoint unavailable: " + e.getMessage();
            listener.problem(problem);
        }
        publish();
        return endpoint != null;
    }

    /**
     * Start-up scan of DOWNSTREAM for the dropbox's own bundles that reached the relay store before the endpoint
     * existed or under other keys (R7 §4.1). Successfully processed or already-processed bundles are removed,
     * since no client could ever use them; a bundle that fails processing stays in the store for retry.
     *
     * @return number of own bundles safely removed from the relay store
     */
    public synchronized int adoptOwnBundles() {
        if (endpoint == null) return 0;
        int removed = 0;
        for (BundleRecord r : store.list(Direction.DOWNSTREAM)) {
            Ownership o = endpoint.classify(r.bundleId());
            if (o == Ownership.NOT_MINE) continue;
            boolean safeToDelete = o == Ownership.MINE_ALREADY_PROCESSED;
            if (o == Ownership.MINE_NEW) {
                Path path = store.pathFor(Direction.DOWNSTREAM, r.bundleId());
                if (path != null) {
                    DropboxControlEndpoint.AcceptResult result = endpoint.accept(path, null);
                    safeToDelete = result == DropboxControlEndpoint.AcceptResult.PROCESSED ||
                                   result == DropboxControlEndpoint.AcceptResult.ALREADY_PROCESSED;
                }
            }
            if (!safeToDelete) continue;
            store.delete(Direction.DOWNSTREAM, r.bundleId());
            removed++;
        }
        if (removed > 0) publish();
        return removed;
    }

    /** @return the latest control status (lock-free) */
    public ControlStatus status() {
        return status;
    }

    // ------------------------------------------------------------------------------------------ ControlChannel

    @Override
    public synchronized OwnBundle ownBundleForVisit() {
        if (endpoint == null) return null;
        try {
            ControlStateStore.Snapshot s = state.snapshot();
            long now = wallClock.getAsLong();
            InventoryBuilder.Draft draft = inventory.build(s, now);
            boolean changed = !draft.fingerprint().equals(s.lastReportFingerprint());
            boolean stale = now - s.lastReportAtMillis() >= REPORT_REFRESH_MS || now < s.lastReportAtMillis();
            boolean queue = changed || stale || s.hasUnreportedResults();
            // ADU first, state second: a crash in between re-uses this reportSeq for the next report, which the
            // server then ignores as "not newer" once; the following report (or the refresh) recovers.
            OwnBundle own = endpoint.prepareUpstream(queue ? draft.encoded() : null);
            if (queue) {
                state.recordReportQueued(draft.report().getReportSeq(), draft.fingerprint(), now);
                listener.reportQueued(draft.report().getReportSeq(), draft.report().getUpstream().size(),
                                      draft.report().getDownstream().size());
            }
            publish();
            return own;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not prepare the dropbox's own bundle", e);
            listener.problem("Could not prepare the control bundle: " + e.getMessage());
            return null;
        }
    }

    @Override
    public synchronized Ownership classify(String bundleId) {
        if (endpoint == null) return Ownership.NOT_MINE;
        try {
            return endpoint.classify(bundleId);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not classify " + bundleId, e);
            return Ownership.NOT_MINE;
        }
    }

    @Override
    public synchronized boolean accept(Path stagedJar, String carrierSenderId) {
        if (endpoint == null) return false;
        DropboxControlEndpoint.AcceptResult r = endpoint.accept(stagedJar, carrierSenderId);
        if (r == DropboxControlEndpoint.AcceptResult.FAILED) {
            listener.problem("A server cleanup bundle could not be processed; a carrier will offer it again");
        }
        publish();
        return r == DropboxControlEndpoint.AcceptResult.PROCESSED;
    }

    // ------------------------------------------------------------------------------------------ internals

    /** The endpoint's plan sink; runs inside {@link #accept} or {@link #open} (lock held). */
    private void applyPlan(RetentionPlan plan) throws java.io.IOException {
        PlanExecutor ex = executor;
        if (ex == null) throw new java.io.IOException("plan executor not ready"); // replayed after open
        PlanExecutor.Outcome outcome = ex.apply(plan);
        listener.planApplied(outcome);
    }

    private void publish() {
        DropboxControlEndpoint e = endpoint;
        ControlStateStore st = state;
        if (e == null || st == null) {
            status = ControlStatus.unavailable(serverKeysLabel, problem);
        } else {
            ControlStateStore.Snapshot s = st.snapshot();
            status = new ControlStatus(true, e.clientId(), serverKeysLabel, s.lastReportSeq(),
                                       s.lastReportAtMillis(), s.lastAppliedPlanSeq(), s.lastPlanAtMillis(),
                                       s.deletedUpstreamCount(), s.deletedUpstreamBytes(),
                                       s.deletedDownstreamCount(), s.deletedDownstreamBytes(), problem);
        }
        listener.statusChanged();
    }
}
