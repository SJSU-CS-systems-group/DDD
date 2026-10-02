package net.discdd.bundledropbox.cycle;

import java.util.List;
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
 *       in-flight bundle, then switch) or for the drain cap, measured from the <em>nominal</em> expiry and
 *       never extended by new arrivals. At the cap the gate force-releases every token (cancel hooks
 *       abort in-flight work) and the role is torn down; partial work stays retryable.</li>
 *   <li>Early return: with {@link CycleConfig#returnAfterVisit()}, a successful
 *       {@link VisitCallback#visitFinished} ends the VISIT dwell immediately (same drain rules).</li>
 *   <li>Bootstrap: a fresh dropbox has no server-signed recency blob and the stock client aborts its
 *       exchange without a SUCCESS recency blob (Research/R3), so hosting would be useless. While
 *       {@code hasRecencyBlob} is false, HOST is skipped and VISIT periods loop.</li>
 *   <li>Failures: a failed radio op is retried with exponential backoff, {@link #MAX_ATTEMPTS} attempts in
 *       total; then the controller enters {@link CycleMode#UNAVAILABLE} and retries the same op after
 *       {@link #UNAVAILABLE_RETRY_MS}, or sooner via {@link #retryNow()} (Wi-Fi/permission changes).</li>
 *   <li>{@link #updateConfig(CycleConfig)} takes effect when the next dwell begins; the running dwell and
 *       its drain cap keep the config they started with, so a deadline already shown to the user never
 *       moves.</li>
 * </ul>
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

    // Snapshots readable from any thread (written only on the owner thread).
    private volatile CycleMode mode = CycleMode.STOPPED;
    private volatile long modeStartedAtMillis;
    private volatile long deadlineAtMillis = CycleListener.NO_DEADLINE;
    private volatile boolean paused;

    // Owner-thread state.
    private CycleConfig activeConfig;
    private boolean running;
    private boolean shutdownRequested;
    private long opGen;
    private Op pendingOp;
    private int attempts;
    private long visitGen = -1;
    private Op unavailableOp;
    private TaskScheduler.Cancellable timer;
    private long timerSeq;
    /** Role the operator asked for ({@link #forceVisit()}/{@link #forceHost()}); used once at the next switch. */
    private Role forcedNext;

    /**
     * Production constructor: owns a new {@link ExecutorTaskScheduler} and {@link ActivityGate}.
     *
     * @param radio          radio side ({@code wifi/WifiDirectRadioDriver})
     * @param clock          monotonic clock ({@code SystemClock::elapsedRealtime} on Android)
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
        // The gate fires this on whatever thread closed the last token; hop to the owner thread.
        gate.setDrainListener(() -> post(this::onDrained));
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

    /** Start cycling (service started). The first role is HOST if a recency blob is cached, else VISIT. */
    public void start() {
        post(() -> {
            running = true;
            kick();
        });
    }

    /** Stop cycling: abort in-flight work, tear down the radio, end in {@link CycleMode#STOPPED}. */
    public void stop() {
        post(() -> {
            running = false;
            halt("stop requested");
        });
    }

    /** Operator pause: like {@link #stop()} but remembered as paused until {@link #resume()}. */
    public void pause() {
        paused = true;
        post(() -> halt("paused"));
    }

    /** Undo {@link #pause()}; cycling continues if {@link #start()} was called. */
    public void resume() {
        paused = false;
        post(this::kick);
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
            halt("shutdown");
            if (mode == CycleMode.STOPPED) {
                scheduler.shutdown();
            } else {
                scheduler.schedule(scheduler::shutdown, SHUTDOWN_TIMEOUT_MS);
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

    /** @return the most recently requested config (may not be in force until the next dwell). */
    public CycleConfig config() {
        return requestedConfig;
    }

    // ------------------------------------------------------------------------------------------------
    // Owner-thread state machine
    // ------------------------------------------------------------------------------------------------

    /** Begin the first role if running, not paused and idle. */
    private void kick() {
        if (!running || paused || shutdownRequested || mode != CycleMode.STOPPED) return;
        startRole(blobAvailable() ? Role.HOST : Role.VISIT);
    }

    /** Abort whatever is happening and drive the radio to idle; ends in STOPPED via {@link #onRoleStopped}. */
    private void halt(String why) {
        if (mode == CycleMode.STOPPED) return;
        logger.info("Halting role cycle in " + mode + ": " + why);
        if (mode == CycleMode.UNAVAILABLE) {
            // The failed op left the radio idle (or as idle as it can get); nothing to tear down.
            cancelTimer();
            gate.closeAdmissions();
            gate.forceReleaseAll(why);
            setMode(CycleMode.STOPPED, CycleListener.NO_DEADLINE);
            maybeFinishShutdown();
            return;
        }
        Op stop = Op.stopFor(mode.isHostSide() ? Role.HOST : Role.VISIT);
        if (pendingOp != stop) issueOp(stop); // already stopping: onRoleStopped will see the flags
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
        if (!op.isStart() && (!running || paused)) {
            // Tearing down because of stop/pause: don't loop on a radio that won't stop; just give up.
            setMode(CycleMode.STOPPED, CycleListener.NO_DEADLINE);
            maybeFinishShutdown();
            return;
        }
        if (attempts < MAX_ATTEMPTS) {
            long backoff = RETRY_BACKOFF_BASE_MS << (attempts - 1);
            scheduleTimer(backoff, () -> issueOp(op));
        } else {
            enterUnavailable(op);
        }
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
     * Close admissions; switch now if idle, else drain until the gate empties or the cap passes.
     *
     * @param expiryAt nominal dwell end (or now, for early return). The cap is measured from here, not
     *                 from when this task happened to run, so a late timer can only shorten the drain.
     */
    private void beginDrain(long expiryAt, String why) {
        if (!mode.isDwelling()) return;
        Role role = mode == CycleMode.HOSTING ? Role.HOST : Role.VISIT;
        gate.closeAdmissions();
        if (gate.activeCount() == 0) {
            logger.info(mode + " ended (" + why + "), idle: switching now");
            issueOp(Op.stopFor(role));
            return;
        }
        long capAt = expiryAt + activeConfig.drainCapMs();
        logger.info(mode + " ended (" + why + "), " + gate.activeCount() + " active: draining until cap");
        setMode(role == Role.HOST ? CycleMode.HOST_DRAINING : CycleMode.VISIT_DRAINING, capAt);
        long delay = capAt - clock.nowMillis();
        if (delay <= 0) {
            onDrainCap();
        } else {
            scheduleTimer(delay, this::onDrainCap);
        }
    }

    /** Gate drain listener (re-posted): the in-flight work finished before the cap. */
    private void onDrained() {
        if (!mode.isDraining() || gate.activeCount() != 0) return; // stale or late: already switched
        logger.info(mode + ": drained before cap");
        issueOp(Op.stopFor(mode == CycleMode.HOST_DRAINING ? Role.HOST : Role.VISIT));
    }

    /** Drain cap reached: force-release (cancel hooks abort in-flight work) and switch anyway. */
    private void onDrainCap() {
        if (!mode.isDraining()) return;
        logger.info(mode + ": drain cap reached with " + gate.activeCount() + " active; forcing switch");
        Role role = mode == CycleMode.HOST_DRAINING ? Role.HOST : Role.VISIT;
        gate.forceReleaseAll("drain cap");
        issueOp(Op.stopFor(role));
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
        if (!running || paused || shutdownRequested) {
            setMode(CycleMode.STOPPED, CycleListener.NO_DEADLINE);
            maybeFinishShutdown();
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
        logger.info("Retrying " + op + " after UNAVAILABLE");
        issueOp(op);
    }

    private void maybeFinishShutdown() {
        if (shutdownRequested && mode == CycleMode.STOPPED) scheduler.shutdown();
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

    /** The single state timer (dwell, drain cap, retry backoff or UNAVAILABLE retry). */
    private void scheduleTimer(long delayMs, Runnable task) {
        cancelTimer();
        long seq = timerSeq;
        timer = scheduler.schedule(() -> {
            if (seq != timerSeq) return; // cancelled after it was already queued to run
            timer = null;
            guarded(task);
        }, delayMs);
    }

    private void cancelTimer() {
        timerSeq++;
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    /** Post to the owner thread; dropped (logged) after shutdown. */
    private void post(Runnable task) {
        try {
            scheduler.execute(() -> guarded(task));
        } catch (RejectedExecutionException e) {
            logger.fine("Role cycle task dropped after shutdown");
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
