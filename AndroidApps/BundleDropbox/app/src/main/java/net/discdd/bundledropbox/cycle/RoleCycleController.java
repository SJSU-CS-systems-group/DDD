package net.discdd.bundledropbox.cycle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The oscillating Wi-Fi Direct role timer: the core behavior the v1 dropbox exists to validate
 * (02 Decisions). A phone has one Wi-Fi Direct interface, so the dropbox alternates between
 * <b>HOST</b> (acting as a BundleTransport toward unmodified BundleClients) and <b>VISIT</b> (acting as a
 * client toward unmodified BundleTransports).
 *
 * <h2>Semantics (03 Architecture, "Role cycle")</h2>
 * <ul>
 *   <li>Each role is STARTING → dwell (HOSTING/VISITING) → [DRAINING] → STOPPING. Radio preparation and
 *       teardown never consume dwell time: the client timer starts when {@link RadioDriver#startHosting}
 *       reports ready (group formed, service added); the transport timer starts when
 *       {@link RadioDriver#startVisit} reports ready (discovery started).</li>
 *   <li>On dwell expiry the {@link ActivityGate} closes admissions. If nothing is active the role
 *       switches at once; otherwise the controller waits for the gate's drain listener (finish the
 *       in-flight bundle, then switch) or for the drain cap. The cap is fixed when the drain begins and is
 *       never extended by new arrivals. At the cap the gate force-releases every token (cancel hooks
 *       abort in-flight work) and the role is torn down; partial work stays retryable.</li>
 *   <li>Joined-but-silent clients (HOST only): the gate only learns about a stock client at its first
 *       RPC, but a client that has already joined our group may still be probing the server or doing the
 *       TLS handshake (Audit m5). If {@link RadioDriver#hostedClientCount()} reports more joined clients
 *       than the gate has admitted keys, HOST_DRAINING keeps admissions open for up to
 *       {@link #JOINED_CLIENT_GRACE_MS} (never past the drain cap) so that client's first RPC is
 *       admitted instead of refused; a refused stock client would stamp {@code lastExchange} and skip us
 *       until the advertised id rotates (R3 §7). The DNS-SD record is withdrawn for the whole drain, so
 *       new clients do not discover us during the grace.</li>
 *   <li>Early return: with {@link CycleConfig#returnAfterVisit()}, a successful
 *       {@link VisitCallback#visitFinished} ends the VISIT dwell immediately (same drain rules).</li>
 *   <li>Bootstrap: a fresh dropbox has no server-signed recency blob and the stock client aborts its
 *       exchange without a SUCCESS recency blob (Research/R3), so hosting would be useless. While
 *       {@code hasRecencyBlob} is false, HOST is skipped and VISIT periods loop.</li>
 *   <li>Failures: a failed radio op is retried with exponential backoff, {@link #MAX_ATTEMPTS} attempts in
 *       total (each distinct op gets its own budget). When a START exhausts its attempts the other role is
 *       tried once (HOST only with a recency blob), so a role-specific failure cannot stop the dropbox from
 *       doing the other half of its job. If that fails too, the controller enters
 *       {@link CycleMode#UNAVAILABLE} and retries the last failed op after {@link #UNAVAILABLE_RETRY_MS},
 *       or sooner via {@link #retryNow()} (Wi-Fi/permission changes).</li>
 *   <li>Role lost: if the radio loses the role on its own mid-dwell (hosted group vanished, Wi-Fi Direct
 *       disabled), {@link #roleLost(String)} tears the role down at once instead of waiting out the dwell.</li>
 *   <li>{@link #updateConfig(CycleConfig)} takes effect when the next dwell begins; the running dwell and
 *       its drain cap keep the config they started with, so a deadline already shown to the user never
 *       moves.</li>
 * </ul>
 *
 * <h2>Stop, pause and a radio that will not stop</h2>
 * {@link #stop()} (operator "Stop dropbox", service teardown) and {@link #pause()} both halt: abort work,
 * stop the radio, end in {@link CycleMode#STOPPED}. Pause is remembered until {@link #resume()} and is
 * reported through {@link CycleListener#onPausedChanged(boolean)} so the service can persist it; calling
 * {@code pause()} before {@link #start()} restores a persisted pause without touching the radio.
 * A halt never leaves the radio half torn down on purpose: from UNAVAILABLE it issues one best-effort stop of
 * the failed role (a failed STOP means group removal was never observed, and a failed START may have left a
 * group or a local service behind). If a stop issued by a halt fails, the controller still settles in
 * STOPPED (it must not loop on a radio that refuses to stop) but remembers the role as <em>dirty</em>: the
 * next {@link #start()}/{@link #resume()} runs that stop before the first role, and a later halt
 * (stop/pause/shutdown) tries it once more. Driver stops are idempotent (they clear services and remove any
 * group), so a redundant stop is harmless.
 *
 * <h2>Time base and deep sleep (Audit M1/M2)</h2>
 * Every deadline is an absolute {@link Clock} time ({@code SystemClock.elapsedRealtime} in production,
 * which keeps counting while the CPU is suspended). The {@link TaskScheduler} is only used to <em>wake</em>
 * the owner thread, never to measure time: {@link ExecutorTaskScheduler} delays use
 * {@code System.nanoTime}, which stops in suspend, so a delay handed to it can stretch by however long the
 * device slept. To keep timing correct anyway:
 * <ul>
 *   <li>A timer is armed in slices of at most {@link #TIMER_SLICE_MS}; each wake re-reads the clock and
 *       either fires the timer or re-arms for the remainder. A stretched timer is therefore late by at most
 *       one slice of awake time, not by a whole dwell.</li>
 *   <li>Every event that reaches the owner thread (radio callback, gate admission or drain, operator
 *       command, {@link #hostedClientsChanged()}) first fires an overdue timer, so the client or carrier
 *       whose arrival woke the CPU sees the cycle already caught up.</li>
 *   <li>A dwell expiry detected late starts its drain at <em>detection</em> time
 *       ({@code capAt = max(expiryAt, now) + drainCap}). Measuring the cap from the nominal expiry would
 *       make it look already exceeded after a sleep, and force-cancel the very work (often the client whose
 *       arrival woke us) that the "finish the in-flight bundle, then switch" rule protects. The cap itself,
 *       once the drain has begun, is real time: if it is detected late, in-flight work has had at least the
 *       full cap and is cancelled.</li>
 * </ul>
 * The service additionally holds a partial wake lock ({@code keep_awake}, on by default) so the CPU does not
 * suspend in the first place; the measures above keep the cycle correct if it does anyway.
 *
 * <h2>Threading</h2>
 * All state is owned by one {@link TaskScheduler} thread. Public methods may be called from any thread;
 * they post to that thread and return immediately. Radio callbacks may arrive on any thread and are
 * re-posted. Every radio operation gets a fresh generation number; callbacks whose generation (or
 * operation kind) is not the one currently pending are stale and ignored, so a late "group formed" from an
 * abandoned attempt can never restart a timer. {@link CycleListener}s run on the owner thread.
 *
 * <p>Pure Java (no Android imports) so it is unit-tested with a fake clock, scheduler and radio.
 */
public final class RoleCycleController {
    private static final Logger logger = Logger.getLogger(RoleCycleController.class.getName());

    /** Attempts per radio op (initial + retries) before entering {@link CycleMode#UNAVAILABLE}. */
    public static final int MAX_ATTEMPTS = 3;
    /** Backoff before the first retry; doubles for each further retry (2 s, 4 s). */
    public static final long RETRY_BACKOFF_BASE_MS = 2_000L;
    /** How long {@link CycleMode#UNAVAILABLE} waits before retrying on its own. */
    public static final long UNAVAILABLE_RETRY_MS = 30_000L;
    /** Upper bound on waiting for radio teardown after {@link #shutdown()}. */
    public static final long SHUTDOWN_TIMEOUT_MS = 5_000L;
    /**
     * Longest delay handed to the {@link TaskScheduler} at once. Each wake re-checks the {@link Clock}, so a
     * timer whose scheduler delay stopped counting during CPU suspend is late by at most this much awake
     * time (see class docs, "Time base and deep sleep").
     */
    public static final long TIMER_SLICE_MS = 5_000L;
    /**
     * Longest time HOST_DRAINING keeps admissions open for clients that joined the group but have not sent
     * their first RPC (Audit m5: the window from join to first RPC is about 1-2 s: server probe, TLS
     * handshake). Never extends the drain cap.
     */
    public static final long JOINED_CLIENT_GRACE_MS = 5_000L;
    /** How often {@link RadioDriver#hostedClientCount()} is re-read during the joined-client grace. */
    public static final long JOINED_CLIENT_POLL_MS = 500L;

    /** The two roles. */
    private enum Role { HOST, VISIT }

    /** Radio operations; at most one is pending at a time (stop ops may supersede a pending start). */
    private enum Op {
        START_HOST(Role.HOST, CycleMode.HOST_STARTING),
        STOP_HOST(Role.HOST, CycleMode.HOST_STOPPING),
        START_VISIT(Role.VISIT, CycleMode.VISIT_STARTING),
        STOP_VISIT(Role.VISIT, CycleMode.VISIT_STOPPING);

        final Role role;
        final CycleMode mode;

        Op(Role role, CycleMode mode) {
            this.role = role;
            this.mode = mode;
        }

        boolean isStart() {
            return this == START_HOST || this == START_VISIT;
        }

        static Op stopFor(Role r) {
            return r == Role.HOST ? STOP_HOST : STOP_VISIT;
        }

        static Op startFor(Role r) {
            return r == Role.HOST ? START_HOST : START_VISIT;
        }
    }

    private final RadioDriver radio;
    private final Clock clock;
    private final TaskScheduler scheduler;
    private final ActivityGate gate;
    private final BooleanSupplier hasRecencyBlob;
    private final List<CycleListener> listeners = new CopyOnWriteArrayList<>();

    /** Latest config requested by {@link #updateConfig}; copied into {@link #activeConfig} at dwell start. */
    private volatile CycleConfig requestedConfig;

    // Snapshots readable from any thread (written only on the owner thread, except paused).
    private volatile CycleMode mode = CycleMode.STOPPED;
    private volatile long modeStartedAtMillis;
    private volatile long deadlineAtMillis = CycleListener.NO_DEADLINE;
    private volatile boolean paused;
    private volatile boolean runningSnapshot;

    // Owner-thread state.
    private CycleConfig activeConfig;
    private boolean running;
    private boolean shutdownRequested;
    private long opGen;
    private Op pendingOp;
    private int attempts;
    /** The op whose failure scheduled the pending backoff retry; any other op starts a fresh budget. */
    private Op retryingOp;
    /** True once a START that exhausted its attempts fell back to the other role (reset when a role works). */
    private boolean fallbackTried;
    private long visitGen = -1;
    private Op unavailableOp;
    /** Role the operator asked for ({@link #forceVisit()}/{@link #forceHost()}); used once at the next switch. */
    private Role forcedNext;
    /** Role whose stop failed during a halt: the radio may still hold its group/service (see class docs). */
    private Role dirtyRole;
    /** End of the joined-client grace in HOST_DRAINING, or {@link CycleListener#NO_DEADLINE} when none. */
    private long joinGraceUntil = CycleListener.NO_DEADLINE;
    /** Last pause state reported through {@link CycleListener#onPausedChanged}. */
    private boolean pausedReported;
    /** Futures returned by {@link #stop()} that complete when the controller reaches STOPPED. */
    private final List<CompletableFuture<Void>> stopWaiters = new ArrayList<>();

    // The single state timer: an absolute Clock deadline plus the scheduler wake-up currently armed for it.
    private Runnable timerTask;
    private long timerDueAt;
    private TaskScheduler.Cancellable timerWake;
    private long timerSeq;

    /**
     * Production constructor: owns a new {@link ExecutorTaskScheduler} and {@link ActivityGate}.
     *
     * @param radio          radio side ({@code wifi/WifiDirectRadioDriver})
     * @param clock          monotonic clock that keeps counting in deep sleep ({@code SystemClock::elapsedRealtime})
     * @param hasRecencyBlob true once a SUCCESS recency blob is cached ({@code RecencyBlobStore.current()})
     * @param config         initial timer settings
     */
    public RoleCycleController(RadioDriver radio, Clock clock, BooleanSupplier hasRecencyBlob, CycleConfig config) {
        this(radio, clock, new ExecutorTaskScheduler(), new ActivityGate(), hasRecencyBlob, config);
    }

    /**
     * Fully injected constructor (tests use a fake scheduler/clock/radio).
     *
     * @param scheduler single owner thread for all controller state
     * @param gate      admission gate shared with the RPC server and visit worker
     */
    public RoleCycleController(RadioDriver radio, Clock clock, TaskScheduler scheduler, ActivityGate gate,
                               BooleanSupplier hasRecencyBlob, CycleConfig config) {
        if (radio == null || clock == null || scheduler == null || gate == null || hasRecencyBlob == null ||
                config == null) {
            throw new NullPointerException("RoleCycleController arguments must be non-null");
        }
        this.radio = radio;
        this.clock = clock;
        this.scheduler = scheduler;
        this.gate = gate;
        this.hasRecencyBlob = hasRecencyBlob;
        this.requestedConfig = config;
        this.activeConfig = config;
        // The gate fires these on whatever thread closed the last token / admitted work; hop to the owner
        // thread. An admission is also a wake-up event: post() fires an overdue timer first.
        gate.setDrainListener(() -> post(this::onDrained));
        gate.setAdmissionListener(() -> post(this::onActivity));
    }

    // ------------------------------------------------------------------------------------------------
    // Public API (any thread)
    // ------------------------------------------------------------------------------------------------

    /** @return the admission gate the RPC server ({@code ClientSessionTracker}) and visit worker use. */
    public ActivityGate gate() {
        return gate;
    }

    /** Register a listener for mode changes and radio errors (called on the owner thread). */
    public void addListener(CycleListener l) {
        listeners.add(l);
    }

    /** Remove a listener added with {@link #addListener}. */
    public void removeListener(CycleListener l) {
        listeners.remove(l);
    }

    /**
     * Start cycling (service started, or the operator restarted a stopped dropbox). The first role is HOST
     * if a recency blob is cached, else VISIT. If {@link #pause()} was called first, the radio stays idle
     * until {@link #resume()}.
     */
    public void start() {
        post(() -> {
            running = true;
            runningSnapshot = true;
            // A stop still tearing down is overruled: its futures will not see STOPPED now.
            for (CompletableFuture<Void> f : stopWaiters) f.cancel(false);
            stopWaiters.clear();
            kick();
        });
    }

    /**
     * Stop cycling (operator "Stop dropbox"): abort in-flight work, tear down the radio and end in
     * {@link CycleMode#STOPPED}. Unlike {@link #shutdown()} the controller can be started again. The pause
     * flag is left as it is.
     *
     * @return completes (on the owner thread) once the controller is STOPPED; completes exceptionally if the
     *         controller is shut down first, and is cancelled if {@link #start()} overrules it. A stop the
     *         radio refuses still completes (see class docs, "dirty" role), so callers may follow it with
     *         {@code stopForeground}/{@code stopSelf}.
     */
    public CompletableFuture<Void> stop() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        boolean posted = post(() -> {
            running = false;
            runningSnapshot = false;
            stopWaiters.add(done);
            halt("stop requested");
            completeStopWaitersIfStopped();
        });
        if (!posted) done.completeExceptionally(new IllegalStateException("role cycle already shut down"));
        return done;
    }

    /**
     * Operator pause: like {@link #stop()} but remembered as paused until {@link #resume()}. Reported to
     * {@link CycleListener#onPausedChanged(boolean)} so the service can persist it across restarts; call it
     * before {@link #start()} to restore a persisted pause.
     */
    public void pause() {
        paused = true;
        post(() -> {
            reportPaused();
            halt("paused");
        });
    }

    /** Undo {@link #pause()}; cycling continues if {@link #start()} was called. */
    public void resume() {
        paused = false;
        post(() -> {
            reportPaused();
            kick();
        });
    }

    /**
     * Change timer settings. Applies when the next dwell (HOSTING or VISITING) begins; the current dwell
     * deadline and drain cap are never moved.
     */
    public void updateConfig(CycleConfig config) {
        if (config == null) throw new NullPointerException("config");
        requestedConfig = config;
    }

    /**
     * Retry immediately if {@link CycleMode#UNAVAILABLE} (call when Wi-Fi is enabled or a permission is
     * granted). No-op in any other mode.
     */
    public void retryNow() {
        post(() -> {
            if (mode == CycleMode.UNAVAILABLE) {
                cancelTimer();
                retryFromUnavailable();
            }
        });
    }

    /**
     * The radio lost the current role by itself (hosted group disappeared, Wi-Fi Direct disabled, P2P
     * channel lost). If a role is dwelling or draining, nothing useful can happen until its timer ends, so
     * abort its work and stop it now; the cycle then moves on (next role, or UNAVAILABLE if the radio
     * cannot even be stopped/started). No-op while a radio op is pending (it fails on its own) or idle.
     *
     * @param reason human-readable cause (logged and reported through {@link CycleListener#onCycleError})
     */
    public void roleLost(String reason) {
        post(() -> onRoleLost(reason));
    }

    /**
     * The set of P2P clients joined to the hosted group changed (optional hint from the radio side). Lets the
     * joined-client grace end as soon as the last silent client sends its first RPC or leaves, instead of at
     * the next {@link #JOINED_CLIENT_POLL_MS} poll; also a wake-up event for overdue timers. Any thread.
     */
    public void hostedClientsChanged() {
        post(this::onActivity);
    }

    /**
     * Operator "Visit transport now": end the current HOST dwell early (normal drain rules: admitted
     * clients finish their in-flight bundle, bounded by the drain cap) and start a VISIT next. No-op if a
     * visit is already starting or dwelling, or if the cycle is stopped/paused.
     */
    public void forceVisit() {
        post(() -> forceRole(Role.VISIT, "operator: visit transport now"));
    }

    /**
     * Operator "Host now": end the current VISIT dwell early (the in-flight bundle finishes, bounded by the
     * drain cap) and host next. Ignored without a recency blob, because the stock client aborts its exchange
     * without a SUCCESS recency blob (Research/R3), so hosting would only turn clients away.
     */
    public void forceHost() {
        post(() -> forceRole(Role.HOST, "operator: host now"));
    }

    /**
     * Stop cycling, then release the scheduler thread once the radio is torn down (or after
     * {@link #SHUTDOWN_TIMEOUT_MS}). The controller cannot be restarted afterwards. Call from
     * {@code Service.onDestroy}.
     */
    public void shutdown() {
        post(() -> {
            shutdownRequested = true;
            running = false;
            runningSnapshot = false;
            halt("shutdown");
            if (mode == CycleMode.STOPPED) {
                finishShutdown();
            } else {
                // Plain scheduler delay (not the sliced state timer): a bound on teardown, not a deadline.
                scheduler.schedule(this::finishShutdown, SHUTDOWN_TIMEOUT_MS);
            }
        });
    }

    /** @return current mode (snapshot). */
    public CycleMode mode() {
        return mode;
    }

    /** @return {@link Clock} time the current mode began (snapshot). */
    public long modeStartedAtMillis() {
        return modeStartedAtMillis;
    }

    /** @return {@link Clock} deadline of the current mode or {@link CycleListener#NO_DEADLINE}. */
    public long deadlineAtMillis() {
        return deadlineAtMillis;
    }

    /** @return true between {@link #pause()} and {@link #resume()}. */
    public boolean isPaused() {
        return paused;
    }

    /** @return true between {@link #start()} and {@link #stop()}/{@link #shutdown()} (snapshot). */
    public boolean isRunning() {
        return runningSnapshot;
    }

    /** @return the most recently requested config (may not be in force until the next dwell). */
    public CycleConfig config() {
        return requestedConfig;
    }

    // ------------------------------------------------------------------------------------------------
    // Owner-thread state machine
    // ------------------------------------------------------------------------------------------------

    /** Begin the first role if running, not paused and idle (after cleaning up a dirty radio). */
    private void kick() {
        if (!running || paused || shutdownRequested || mode != CycleMode.STOPPED) return;
        Role first = blobAvailable() ? Role.HOST : Role.VISIT;
        if (dirtyRole != null) {
            Role dirty = dirtyRole;
            dirtyRole = null;
            logger.info("Radio may still hold " + dirty + " state from a failed stop: stopping it before " + first);
            forcedNext = first; // onRoleStopped picks it (and still skips HOST without a blob)
            issueOp(Op.stopFor(dirty));
            return;
        }
        startRole(first);
    }

    /** Abort whatever is happening and drive the radio to idle; ends in STOPPED via {@link #onRoleStopped}. */
    private void halt(String why) {
        if (mode == CycleMode.STOPPED) {
            if (dirtyRole != null) {
                Role dirty = dirtyRole;
                dirtyRole = null;
                logger.info("Halting (" + why + "): one more best-effort stop of dirty " + dirty);
                issueOp(Op.stopFor(dirty));
            }
            return;
        }
        logger.info("Halting role cycle in " + mode + ": " + why);
        if (mode == CycleMode.UNAVAILABLE) {
            // The radio is not known to be idle here: a failed STOP means group removal was never observed,
            // and a failed START may have left a group or local service behind (Audit m4/m22). Issue one
            // best-effort stop of that role; if it fails too, onOpFailed settles in STOPPED and marks it dirty.
            Op failed = unavailableOp;
            unavailableOp = null;
            issueOp(Op.stopFor(failed != null ? failed.role : Role.HOST));
            return;
        }
        Op stop = Op.stopFor(mode.isHostSide() ? Role.HOST : Role.VISIT);
        if (pendingOp != stop) issueOp(stop); // already stopping: onRoleStopped will see the flags
    }

    /** @return true when a stop/pause/shutdown is in effect, so a finished stop must end in STOPPED. */
    private boolean halting() {
        return !running || paused || shutdownRequested;
    }

    private void startRole(Role role) {
        attempts = 0;
        if (role == Role.VISIT && !blobAvailable()) {
            logger.info("No recency blob cached: bootstrap VISIT (HOST skipped until a carrier provides one)");
        }
        issueOp(Op.startFor(role));
    }

    /** Issue one radio op with a fresh generation. */
    private void issueOp(Op op) {
        cancelTimer();
        joinGraceUntil = CycleListener.NO_DEADLINE;
        if (op != retryingOp) {
            // A different op (e.g. the STOP that supersedes a failing START after "Visit now") gets its own
            // attempt budget; only a backoff retry of the same op keeps counting.
            attempts = 0;
            retryingOp = null;
        }
        if (op.isStart()) {
            // Admit work as soon as the role is being prepared so a fast first client/carrier is never
            // rejected in the window between the radio being usable and "ready" reaching this thread.
            gate.openAdmissions();
        } else {
            // Normally nothing is active here (we drained); after the cap, a pause or a stop, abort
            // anything left so the radio can be torn down.
            gate.closeAdmissions();
            gate.forceReleaseAll("role " + op.role + " stopping");
            if (op.role == Role.VISIT) visitGen = -1;
        }
        long gen = ++opGen;
        pendingOp = op;
        if (mode != op.mode) setMode(op.mode, CycleListener.NO_DEADLINE);
        VisitCallback cb = callbackFor(op);
        try {
            switch (op) {
                case START_HOST -> radio.startHosting(gen, cb);
                case STOP_HOST -> radio.stopHosting(gen, cb);
                case START_VISIT -> radio.startVisit(gen, cb);
                case STOP_VISIT -> radio.stopVisit(gen, cb);
            }
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "RadioDriver threw for " + op, e);
            post(() -> onOpFailed(op, gen, "driver threw: " + e));
        }
    }

    /** Callback that hops to the owner thread; generation checks happen there. */
    private VisitCallback callbackFor(Op op) {
        return new VisitCallback() {
            @Override
            public void ready(long gen) {
                post(() -> onOpReady(op, gen));
            }

            @Override
            public void failed(long gen, String reason) {
                post(() -> onOpFailed(op, gen, reason));
            }

            @Override
            public void visitFinished(long gen, boolean success) {
                post(() -> onVisitFinished(gen, success));
            }
        };
    }

    private boolean isCurrent(Op op, long gen) {
        if (gen == opGen && pendingOp == op) return true;
        logger.fine("Ignoring stale callback " + op + " gen=" + gen + " (current gen=" + opGen + ", pending=" +
                            pendingOp + ")");
        return false;
    }

    private void onOpReady(Op op, long gen) {
        if (!isCurrent(op, gen)) return;
        pendingOp = null;
        attempts = 0;
        retryingOp = null;
        if (op.isStart()) fallbackTried = false; // a role works again: a later failure may fall back anew
        switch (op) {
            case START_HOST -> enterDwell(CycleMode.HOSTING);
            case START_VISIT -> {
                visitGen = gen;
                enterDwell(CycleMode.VISITING);
            }
            case STOP_HOST -> onRoleStopped(Role.HOST);
            case STOP_VISIT -> onRoleStopped(Role.VISIT);
        }
    }

    private void onOpFailed(Op op, long gen, String reason) {
        if (!isCurrent(op, gen)) return;
        pendingOp = null;
        attempts++;
        logger.warning(op + " failed (attempt " + attempts + "/" + MAX_ATTEMPTS + "): " + reason);
        notifyError(reason);
        if (!op.isStart() && halting()) {
            // A halt makes one best-effort stop: don't loop on a radio that won't stop. The radio may still
            // hold this role's group/service, so remember it; the next start (or halt) stops it first.
            dirtyRole = op.role;
            logger.warning(op + " failed while halting; settling in STOPPED with " + op.role + " possibly dirty");
            setMode(CycleMode.STOPPED, CycleListener.NO_DEADLINE);
            return;
        }
        if (attempts < MAX_ATTEMPTS) {
            retryingOp = op;
            long backoff = RETRY_BACKOFF_BASE_MS << (attempts - 1);
            scheduleTimer(backoff, () -> issueOp(op));
            return;
        }
        Role other = op.role == Role.HOST ? Role.VISIT : Role.HOST;
        if (op.isStart() && !fallbackTried && (other == Role.VISIT || blobAvailable())) {
            // One role can fail for a reason the other does not share (e.g. a VISIT-only discovery problem,
            // or createGroup failing while joining still works). Try the other role before parking, so a
            // dropbox that can still do half its job keeps doing it instead of looping in UNAVAILABLE.
            fallbackTried = true;
            logger.warning(op + " exhausted " + MAX_ATTEMPTS + " attempts; trying " + other + " instead");
            startRole(other);
            return;
        }
        enterUnavailable(op);
    }

    /** Dwell begins now that the radio is ready; the timer never includes preparation time. */
    private void enterDwell(CycleMode dwellMode) {
        activeConfig = requestedConfig; // updateConfig takes effect here
        long period = dwellMode == CycleMode.HOSTING ? activeConfig.clientPeriodMs() :
                activeConfig.transportPeriodMs();
        long expiryAt = clock.nowMillis() + period;
        setMode(dwellMode, expiryAt);
        scheduleTimer(period, () -> beginDrain(expiryAt, "dwell expired"));
    }

    /**
     * Close admissions (unless joined HOST clients still need their first RPC); switch now if idle, else
     * drain until the gate empties or the cap passes.
     *
     * @param expiryAt nominal dwell end (or now, for early return / operator switch). The drain starts at
     *                 {@code max(expiryAt, now)}: an expiry detected late (the device slept through the
     *                 timer) still gets the full drain cap from detection, so in-flight work is not
     *                 force-cancelled just because the wake-up was late (class docs, "Time base").
     */
    private void beginDrain(long expiryAt, String why) {
        if (!mode.isDwelling()) return;
        Role role = mode == CycleMode.HOSTING ? Role.HOST : Role.VISIT;
        long now = clock.nowMillis();
        long drainStart = Math.max(expiryAt, now);
        if (now - expiryAt > TIMER_SLICE_MS) {
            logger.info(mode + " expiry detected " + (now - expiryAt) + " ms late (CPU suspended?): drain " +
                                "measured from detection");
        }
        long capAt = drainStart + activeConfig.drainCapMs();
        int unadmitted = role == Role.HOST ? unadmittedJoinedClients() : 0;
        boolean grace = unadmitted > 0 && capAt > now;
        if (!grace) {
            gate.closeAdmissions();
            if (gate.activeCount() == 0) {
                logger.info(mode + " ended (" + why + "), idle: switching now");
                issueOp(Op.stopFor(role));
                return;
            }
        }
        logger.info(mode + " ended (" + why + "), " + gate.activeCount() + " active" +
                            (grace ? ", " + unadmitted + " joined client(s) not yet admitted: grace" : "") +
                            ": draining until cap");
        if (role == Role.HOST) {
            // New clients are refused once admissions close, and a refused stock client stamps lastExchange
            // for our advertised id anyway and then skips us until the id rotates (R3 §7). Stop advertising
            // so nobody new discovers us; admitted (and joined) clients keep the group and endpoint.
            try {
                radio.withdrawHostAdvertisement();
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "withdrawHostAdvertisement threw", e);
            }
        }
        setMode(role == Role.HOST ? CycleMode.HOST_DRAINING : CycleMode.VISIT_DRAINING, capAt);
        if (grace) {
            joinGraceUntil = Math.min(drainStart + JOINED_CLIENT_GRACE_MS, capAt);
            scheduleTimer(Math.min(JOINED_CLIENT_POLL_MS, joinGraceUntil - now), this::checkJoinGrace);
            return;
        }
        armDrainCap(capAt, now);
    }

    /** Arm the drain-cap timer for an already-fixed {@code capAt}; a cap already reached forces now. */
    private void armDrainCap(long capAt, long now) {
        long delay = capAt - now;
        if (delay <= 0) {
            onDrainCap();
        } else {
            scheduleTimer(delay, this::onDrainCap);
        }
    }

    /**
     * Joined-client grace (HOST_DRAINING with admissions still open): keep waiting while a joined client has
     * no admitted session and the grace lasts; then close admissions and continue as an ordinary drain with
     * the cap fixed at {@link #beginDrain}.
     */
    private void checkJoinGrace() {
        if (mode != CycleMode.HOST_DRAINING || joinGraceUntil == CycleListener.NO_DEADLINE) return;
        long now = clock.nowMillis();
        int unadmitted = unadmittedJoinedClients();
        if (unadmitted > 0 && now < joinGraceUntil) {
            scheduleTimer(Math.min(JOINED_CLIENT_POLL_MS, joinGraceUntil - now), this::checkJoinGrace);
            return;
        }
        joinGraceUntil = CycleListener.NO_DEADLINE;
        gate.closeAdmissions();
        logger.info("Joined-client grace over (" + (unadmitted > 0 ? unadmitted + " still silent" :
                "every joined client admitted or gone") + "), " + gate.activeCount() + " active");
        if (gate.activeCount() == 0) {
            issueOp(Op.stopFor(Role.HOST));
            return;
        }
        armDrainCap(deadlineAtMillis, now);
    }

    /**
     * @return joined group clients that hold no gate key yet (0 if the driver cannot tell). Compares counts
     *         because the driver knows clients by MAC and the gate by IP; a client that already finished but
     *         stays joined also counts, which costs at most {@link #JOINED_CLIENT_GRACE_MS}.
     */
    private int unadmittedJoinedClients() {
        int joined;
        try {
            joined = radio.hostedClientCount();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "hostedClientCount threw; assuming no joined clients", e);
            return 0;
        }
        return Math.max(0, joined - gate.activeKeyCount());
    }

    /** Gate drain listener (re-posted): the in-flight work finished before the cap. */
    private void onDrained() {
        if (!mode.isDraining() || gate.activeCount() != 0) return; // stale or late: already switched
        if (joinGraceUntil != CycleListener.NO_DEADLINE) return; // the grace check decides
        logger.info(mode + ": drained before cap");
        issueOp(Op.stopFor(mode == CycleMode.HOST_DRAINING ? Role.HOST : Role.VISIT));
    }

    /** Gate admission or joined-client change (re-posted): lets the grace end without waiting for a poll. */
    private void onActivity() {
        if (joinGraceUntil != CycleListener.NO_DEADLINE) checkJoinGrace();
    }

    /** Drain cap reached: force-release (cancel hooks abort in-flight work) and switch anyway. */
    private void onDrainCap() {
        if (!mode.isDraining()) return;
        logger.info(mode + ": drain cap reached with " + gate.activeCount() + " active; forcing switch");
        Role role = mode == CycleMode.HOST_DRAINING ? Role.HOST : Role.VISIT;
        gate.forceReleaseAll("drain cap");
        issueOp(Op.stopFor(role));
    }

    private void onRoleLost(String reason) {
        if (!mode.isDwelling() && !mode.isDraining()) {
            logger.fine("roleLost(" + reason + ") ignored in " + mode);
            return;
        }
        Role role = mode.isHostSide() ? Role.HOST : Role.VISIT;
        logger.warning(mode + ": role lost (" + reason + "); stopping " + role + " now");
        notifyError(reason);
        issueOp(Op.stopFor(role)); // closes admissions and force-releases whatever was still active
    }

    private void onVisitFinished(long gen, boolean success) {
        if (gen != visitGen || mode != CycleMode.VISITING) {
            // Stale visit, or already draining (the worker's token close drives the switch then).
            logger.fine("visitFinished(gen=" + gen + ", " + success + ") ignored in " + mode);
            return;
        }
        logger.info("Carrier exchange finished, success=" + success);
        if (success && activeConfig.returnAfterVisit()) {
            beginDrain(clock.nowMillis(), "early return after successful visit");
        }
    }

    /**
     * Operator-requested switch. Reuses the ordinary paths so every timer/drain invariant still holds: a
     * dwelling role ends through {@link #beginDrain} (as on expiry, with expiry = now), a role that is still
     * starting is stopped (its late ready is then stale by generation), and a role already draining or
     * stopping just has its successor overridden.
     */
    private void forceRole(Role target, String why) {
        if (!running || paused || shutdownRequested || mode == CycleMode.STOPPED) {
            logger.info("Ignoring '" + why + "' in " + mode + (paused ? " (paused)" : ""));
            return;
        }
        if (target == Role.HOST && !blobAvailable()) {
            logger.info("Ignoring '" + why + "': no recency blob yet, clients would abort (bootstrap)");
            return;
        }
        if (mode == CycleMode.UNAVAILABLE) {
            // A failed start is replaced by the requested start; a failed stop must still be retried first
            // and the requested role follows it.
            if (unavailableOp != null && unavailableOp.isStart()) {
                unavailableOp = Op.startFor(target);
            } else {
                forcedNext = target;
            }
            cancelTimer();
            retryFromUnavailable();
            return;
        }
        Role current = mode.isHostSide() ? Role.HOST : Role.VISIT;
        boolean startingOrDwelling = mode.isDwelling() || mode == CycleMode.HOST_STARTING ||
                mode == CycleMode.VISIT_STARTING;
        if (current == target && startingOrDwelling) {
            logger.info("Ignoring '" + why + "': already in " + mode);
            return;
        }
        forcedNext = target;
        logger.info(why + " (in " + mode + ")");
        if (mode.isDwelling()) {
            beginDrain(clock.nowMillis(), why);
        } else if (startingOrDwelling) {
            issueOp(Op.stopFor(current)); // supersedes the pending start
        }
        // DRAINING/STOPPING: the switch is already under way; onRoleStopped picks forcedNext.
    }

    /** Radio is idle after a role; pick the next role or settle in STOPPED. */
    private void onRoleStopped(Role finished) {
        Role forced = forcedNext;
        forcedNext = null;
        if (halting()) {
            setMode(CycleMode.STOPPED, CycleListener.NO_DEADLINE);
            return;
        }
        // Bootstrap: without a recency blob clients abort (R3), so keep visiting until a carrier gives one.
        Role next = forced != null ? forced : (finished == Role.HOST ? Role.VISIT : Role.HOST);
        if (next == Role.HOST && !blobAvailable()) next = Role.VISIT;
        startRole(next);
    }

    private void enterUnavailable(Op failedOp) {
        unavailableOp = failedOp;
        attempts = 0;
        gate.closeAdmissions();
        gate.forceReleaseAll("radio unavailable");
        setMode(CycleMode.UNAVAILABLE, clock.nowMillis() + UNAVAILABLE_RETRY_MS);
        scheduleTimer(UNAVAILABLE_RETRY_MS, this::retryFromUnavailable);
    }

    /** Retry the op that exhausted its attempts, with a fresh attempt budget. */
    private void retryFromUnavailable() {
        if (mode != CycleMode.UNAVAILABLE || unavailableOp == null) return;
        Op op = unavailableOp;
        unavailableOp = null;
        attempts = 0;
        retryingOp = null;
        fallbackTried = false; // fresh budget: if this role still fails, the other one gets a turn again
        logger.info("Retrying " + op + " after UNAVAILABLE");
        issueOp(op);
    }

    /** Owner thread: STOPPED was entered (or shutdown timed out). Completes stop futures, finishes shutdown. */
    private void onStopped() {
        completeStopWaitersIfStopped();
        if (shutdownRequested) finishShutdown();
    }

    private void completeStopWaitersIfStopped() {
        if (mode != CycleMode.STOPPED || stopWaiters.isEmpty()) return;
        List<CompletableFuture<Void>> done = new ArrayList<>(stopWaiters);
        stopWaiters.clear();
        for (CompletableFuture<Void> f : done) f.complete(null);
    }

    /** Release the scheduler; stop futures still waiting (teardown timed out) complete exceptionally. */
    private void finishShutdown() {
        if (!stopWaiters.isEmpty()) {
            List<CompletableFuture<Void>> left = new ArrayList<>(stopWaiters);
            stopWaiters.clear();
            IllegalStateException e = new IllegalStateException("shut down before the radio stopped (" + mode + ")");
            for (CompletableFuture<Void> f : left) f.completeExceptionally(e);
        }
        scheduler.shutdown();
    }

    /** Tell listeners about a pause change (owner thread; the volatile flag was set by the caller). */
    private void reportPaused() {
        boolean now = paused;
        if (now == pausedReported) return;
        pausedReported = now;
        for (CycleListener l : listeners) {
            try {
                l.onPausedChanged(now);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "CycleListener threw", e);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------

    private boolean blobAvailable() {
        try {
            return hasRecencyBlob.getAsBoolean();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "hasRecencyBlob threw; treating as no blob", e);
            return false;
        }
    }

    private void setMode(CycleMode next, long deadline) {
        CycleMode prev = mode;
        long now = clock.nowMillis();
        mode = next;
        modeStartedAtMillis = now;
        deadlineAtMillis = deadline;
        logger.info("Cycle " + prev + " -> " + next +
                            (deadline == CycleListener.NO_DEADLINE ? "" : " (deadline in " + (deadline - now) + " ms)"));
        for (CycleListener l : listeners) {
            try {
                l.onModeChanged(prev, next, now, deadline);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "CycleListener threw", e);
            }
        }
        if (next == CycleMode.STOPPED) onStopped();
    }

    private void notifyError(String reason) {
        for (CycleListener l : listeners) {
            try {
                l.onCycleError(mode, reason);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "CycleListener threw", e);
            }
        }
    }

    /**
     * Arm the single state timer (dwell, drain cap, joined-client poll, retry backoff or UNAVAILABLE retry)
     * to run {@code task} at {@link Clock} time now + {@code delayMs}. The deadline lives on the clock; the
     * scheduler only provides wake-ups of at most {@link #TIMER_SLICE_MS} (class docs, "Time base").
     */
    private void scheduleTimer(long delayMs, Runnable task) {
        cancelTimer();
        timerTask = task;
        timerDueAt = clock.nowMillis() + Math.max(0, delayMs);
        armTimerWake();
    }

    private void armTimerWake() {
        long seq = timerSeq;
        long wait = Math.min(Math.max(0, timerDueAt - clock.nowMillis()), TIMER_SLICE_MS);
        timerWake = scheduler.schedule(() -> {
            if (seq != timerSeq) return; // cancelled after it was already queued to run
            timerWake = null;
            guarded(this::onTimerWake);
        }, wait);
    }

    /** A scheduler wake-up: fire the timer if its clock deadline passed, else sleep another slice. */
    private void onTimerWake() {
        if (timerTask == null) return;
        if (clock.nowMillis() >= timerDueAt) {
            fireTimer();
        } else {
            armTimerWake();
        }
    }

    /** Before every owner-thread event: a timer whose deadline passed while the CPU slept fires first. */
    private void fireOverdueTimer() {
        if (timerTask == null) return;
        long late = clock.nowMillis() - timerDueAt;
        if (late < 0) return;
        if (late > 0) logger.info("State timer overdue by " + late + " ms (CPU suspended?): firing on wake-up");
        fireTimer();
    }

    private void fireTimer() {
        Runnable task = timerTask;
        cancelTimer();
        task.run();
    }

    private void cancelTimer() {
        timerSeq++;
        timerTask = null;
        if (timerWake != null) {
            timerWake.cancel();
            timerWake = null;
        }
    }

    /**
     * Post to the owner thread; every posted event first fires an overdue timer (class docs, "Time base").
     *
     * @return false if dropped because the scheduler has shut down
     */
    private boolean post(Runnable task) {
        try {
            scheduler.execute(() -> {
                guarded(this::fireOverdueTimer);
                guarded(task);
            });
            return true;
        } catch (RejectedExecutionException e) {
            logger.fine("Role cycle task dropped after shutdown");
            return false;
        }
    }

    /** Keep a bug in one task from killing the owner thread or silently stopping the cycle. */
    private static void guarded(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Role cycle task failed", e);
        }
    }
}
