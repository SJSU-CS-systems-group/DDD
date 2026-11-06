package net.discdd.bundledropbox.control;

import net.discdd.bundledropbox.status.ControlStatus;
import net.discdd.bundledropbox.store.BundleRecord;
import net.discdd.bundledropbox.store.Direction;
import net.discdd.bundledropbox.store.FileDeliveryLog;
import net.discdd.bundledropbox.store.RecencyBlobStore;
import net.discdd.bundledropbox.store.RelayStore;
import net.discdd.bundledropbox.visit.ControlChannel;
import net.discdd.dropbox.control.DropboxControlCodec;
import net.discdd.dropbox.control.RecencyGrant;
import net.discdd.dropbox.control.RecencyRequestSigner;
import net.discdd.dropbox.control.RetentionPlan;

import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyPair;
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
 *       TTL); return the endpoint's newest bundle. For partial inventories, keep the current page until a
 *       matching plan arrives; refreshes resend that page and a matching plan permits the next page. For a
 *       complete inventory, an empty plan alone does not trigger another report
 *       ({@link InventoryBuilder#fingerprint}).</li>
 *   <li>In the same step, the recency request (09 Own Recency Blob Design): if no request is outstanding, sign the
 *       next one ({@code RecencyRequestSigner.sign(endpointClientId, recencyRequestSeq + 1, tlsKeyPair)}) and queue
 *       it under {@code RECENCY_APP_ID}, which forces a new own bundle. An outstanding request is left alone: it
 *       still rides in every bundle until the server acknowledges one that carried it, and re-queuing it would
 *       burn a new bundle id per visit. So the dropbox keeps exactly one request outstanding, like a transport
 *       that fetches a fresh blob on every server sync.</li>
 *   <li>{@link #classify} / {@link #accept}: bundles addressed to the dropbox are processed by the endpoint, whose
 *       plans reach {@link PlanExecutor#apply} through the endpoint's plan sink and whose recency grants reach the
 *       {@link RecencyBlobStore} through its grant sink. The store keeps a granted blob only if it is signed by the
 *       trusted server and minted for the dropbox's own sender id. A grant whose {@code requestSeq} is at least the
 *       outstanding one answers it, whether or not its blob was usable, so the next visit asks again.</li>
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

        /** A new recency request was queued for upload. */
        default void recencyRequested(long requestSeq) {}

        /**
         * A recency grant was received.
         *
         * @param requestSeq the request it answers
         * @param stored     true if its blob is now the dropbox's recency blob
         */
        default void recencyGranted(long requestSeq, boolean stored) {}

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
    private final RecencyBlobStore recency;
    private final KeyPair tlsKeyPair;

    private DropboxControlEndpoint endpoint; // guarded by this; null while unavailable
    private ControlStateStore state; // guarded by this
    private PlanExecutor executor; // guarded by this
    private String serverKeysLabel = ""; // guarded by this
    private String problem; // guarded by this
    private volatile ControlStatus status = ControlStatus.NONE;

    /**
     * Storage management only: no recency requests are sent and received grants are dropped (tests).
     *
     * @see #DropboxControlManager(Path, RelayStore, Supplier, Runnable, LongSupplier, Listener, RecencyBlobStore,
     *      KeyPair)
     */
    public DropboxControlManager(Path root, RelayStore store, Supplier<List<FileDeliveryLog.Served>> servedEvents,
                                 Runnable afterDeletions, LongSupplier wallClock, Listener listener) {
        this(root, store, servedEvents, afterDeletions, wallClock, listener, null, null);
    }

    /**
     * @param root           endpoint root ({@code getDataDir()/ControlEndpoint})
     * @param store          the relay store (inventory source, deletion target)
     * @param servedEvents   delivery-log events ({@code FileDeliveryLog::readAll})
     * @param afterDeletions run after a plan deleted bundles (prune the forward ledger)
     * @param wallClock      epoch millis
     * @param listener       service reactions
     * @param recency        where granted blobs are offered (it verifies them), or {@code null} for none
     * @param tlsKeyPair     the dropbox TLS EC key pair (the one its gRPC certificate presents) that requests are
     *                       signed with, or {@code null} to send no requests
     */
    public DropboxControlManager(Path root, RelayStore store, Supplier<List<FileDeliveryLog.Served>> servedEvents,
                                 Runnable afterDeletions, LongSupplier wallClock, Listener listener,
                                 RecencyBlobStore recency, KeyPair tlsKeyPair) {
        this.root = Objects.requireNonNull(root, "root");
        this.store = Objects.requireNonNull(store, "store");
        this.afterDeletions = Objects.requireNonNull(afterDeletions, "afterDeletions");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.inventory = new InventoryBuilder(store, servedEvents);
        this.recency = recency;
        this.tlsKeyPair = tlsKeyPair;
    }

    /**
     * Opens the endpoint for the given server keys, replacing any open one (keys changed). Replays plans and
     * grants that were stored but not applied before the last shutdown. Different server keys wipe the endpoint
     * and with it the control state, so the recency request numbering starts over ({@link #wasResetForNewServer}
     * tells the service to drop the old server's blob too).
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
                                                                        this::applyPlan, this::applyGrant);
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
                    DropboxControlEndpoint.AcceptResult result = endpoint.accept(path);
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

    /**
     * @return true if the open endpoint was created fresh because it held state for other server keys (the
     *         recency request state started over with it); false if it kept its state or is unavailable
     */
    public synchronized boolean wasResetForNewServer() {
        return endpoint != null && endpoint.wasResetForNewServer();
    }

    /**
     * Operator recovery: forgets the outstanding recency request, so the next visit signs and queues a new one.
     * The escape from a request the server will never answer; the old request still rides until acknowledged and
     * the server answers the newest one.
     */
    public synchronized void abandonRecencyRequest() {
        if (endpoint == null) return;
        try {
            state.recordRecencyAbandoned(wallClock.getAsLong());
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not forget the outstanding recency request", e);
            listener.problem("Could not reset the recency request: " + e.getMessage());
        }
        publish();
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
            long requestSeq = s.recencyOutstanding() ? 0 : s.recencyRequestSeq() + 1;
            byte[] request = requestSeq > 0 ? signRecencyRequest(requestSeq) : null;
            boolean stale = now - s.lastReportAtMillis() >= REPORT_REFRESH_MS || now < s.lastReportAtMillis();
            OwnBundle own;
            if (s.hasPendingPage() && !stale && s.latestPendingReportSeq() != 0 &&
                    s.lastReportSeq() == s.latestPendingReportSeq()) {
                own = endpoint.prepareUpstream(null, request);
            } else {
                InventoryBuilder.Draft draft = inventory.build(s, now);
                boolean changed = !draft.fingerprint().equals(s.lastReportFingerprint());
                // A partial page stays in place until a plan based on that exact report is applied. A refresh
                // re-sends the same logical page with a new sequence if its plan was lost; older queued sequences
                // remain valid acknowledgements for that page.
                boolean queue = s.hasPendingPage() ?
                        stale || s.lastReportSeq() > s.latestPendingReportSeq() :
                        changed || stale || s.hasUnreportedResults();
                // ADU first, state second: a crash in between re-uses this reportSeq for the next report, which the
                // server then ignores as "not newer" once; the following report (or the refresh) recovers.
                own = endpoint.prepareUpstream(queue ? draft.encoded() : null, request);
                if (queue) {
                    state.recordInventoryQueued(draft, now);
                    listener.reportQueued(draft.report().getReportSeq(), draft.report().getUpstream().size(),
                                          draft.report().getDownstream().size());
                }
            }
            if (request != null) {
                // ADU first, state second here too: a crash in between re-sends the same requestSeq, which the
                // server answers like the first one. The other order could leave a request outstanding forever.
                state.recordRecencyRequested(requestSeq, now);
                listener.recencyRequested(requestSeq);
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
    public synchronized boolean accept(Path stagedJar) {
        if (endpoint == null) return false;
        DropboxControlEndpoint.AcceptResult r = endpoint.accept(stagedJar);
        if (r == DropboxControlEndpoint.AcceptResult.FAILED) {
            listener.problem("A server cleanup bundle could not be processed; a carrier will offer it again");
        }
        publish();
        return r == DropboxControlEndpoint.AcceptResult.PROCESSED;
    }

    // ------------------------------------------------------------------------------------------ internals

    /**
     * @return the encoded, signed recency request {@code requestSeq}, or {@code null} if the dropbox takes no part
     *         in the recency exchange or signing failed (logged; the inventory still goes out)
     */
    private byte[] signRecencyRequest(long requestSeq) {
        if (tlsKeyPair == null || recency == null) return null;
        try {
            return DropboxControlCodec.encode(RecencyRequestSigner.sign(endpoint.clientId(), requestSeq, tlsKeyPair));
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not sign recency request #" + requestSeq, e);
            listener.problem("Could not sign the recency request: " + e.getMessage());
            return null;
        }
    }

    /**
     * The endpoint's grant sink; runs inside {@link #accept} or {@link #open} (lock held). Offers the granted blob
     * to the store, which keeps it only if it is server-signed, minted for the dropbox's own sender id and newer
     * than the one it holds, then records the answer.
     */
    private void applyGrant(RecencyGrant grant) throws IOException {
        ControlStateStore st = state;
        if (st == null) throw new IOException("control state not ready"); // replayed after open
        boolean stored = false;
        if (recency == null) {
            logger.log(Level.INFO, "No recency store; ignoring " + grant);
        } else {
            stored = recency.offer(grant.getRecencyBlobBytes());
            logger.log(stored ? Level.INFO : Level.WARNING, "Recency grant for request #" + grant.getRequestSeq() +
                    (stored ? " stored" : " not stored (rejected or not newer)"));
        }
        st.recordRecencyGranted(grant.getRequestSeq(), stored, wallClock.getAsLong());
        listener.recencyGranted(grant.getRequestSeq(), stored);
    }

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
                                       s.deletedDownstreamCount(), s.deletedDownstreamBytes(), problem)
                    .withRecency(s.recencyRequestSeq(), s.recencyRequestedAtMillis(), s.recencyAnsweredSeq(),
                                 s.recencyGrantAtMillis(), s.recencyAbandonedAtMillis());
        }
        listener.statusChanged();
    }
}
