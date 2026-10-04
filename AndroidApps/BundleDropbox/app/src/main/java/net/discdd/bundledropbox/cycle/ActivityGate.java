package net.discdd.bundledropbox.cycle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Admission control and drain tracking for the work done during one role period.
 *
 * <p>The {@link RoleCycleController} opens the gate when a role starts and closes it when the dwell
 * timer expires (02 Decisions, "flexible" timer: finish the in-flight bundle, then switch). Work that
 * must finish before the radio is torn down holds a {@link Token}:
 * <ul>
 *   <li><b>HOST side</b> ({@code rpc/ClientSessionTracker}): the stock BundleClient has no exchange
 *       session id, so the key is the client's remote IP (03 Architecture, "Client-side keying"). The
 *       first RPC admits the session; later RPCs from an admitted IP must still pass while draining,
 *       otherwise a client that already uploaded its first chunk would be cut off mid-exchange. That is
 *       why {@link #tryAdmit(String)} still succeeds for a key that has a live token while the gate is
 *       closed. The tracker holds one session token per IP until {@code uploadBundle} completes or the
 *       session is reaped idle (10 s, only while admissions are open); in-flight RPCs are counted in the
 *       tracker, not as extra tokens.</li>
 *   <li><b>VISIT side</b> ({@code service/VisitCoordinator}, the joiner's visit runner): one token, taken
 *       when the carrier connect starts ({@code beginJoin}, audit m1), covers the join and the whole carrier
 *       exchange. {@code TransportVisitClient} gets {@code !isClosing()} as its
 *       keep-going check, so it finishes the in-flight bundle and returns once admissions close; the
 *       runner then closes the token, and the token's cancel hook hard-cancels the exchange at the drain
 *       cap.</li>
 * </ul>
 *
 * <p>Admissions are normally closed at dwell expiry. The one exception is the controller's joined-client
 * grace in HOST_DRAINING (see {@code RoleCycleController}): admissions stay open for a few seconds so a
 * client that joined our group but has not sent its first RPC yet is admitted rather than refused.
 *
 * <p>The gate is role-agnostic: every START (HOST or VISIT) opens it. The client-facing gRPC server
 * listens for the whole service lifetime ({@code rpc/DropboxRpcServer}), so admission, not the socket, is
 * what refuses clients between roles; during VISIT no stock client can reach it anyway, because the dropbox
 * is then a group client, not a group owner.
 *
 * <p>When admissions are closed and the last live token closes, the drain listener fires (the
 * controller then switches role without waiting for the drain cap). When the cap is reached the
 * controller calls {@link #forceReleaseAll(String)}: every token is invalidated and its cancel hook runs,
 * so in-flight work can abort; partial downloads stay retryable because the store only commits complete
 * bundles.
 *
 * <p>Thread-safe: tokens are taken and closed on gRPC and visit worker threads. Listeners and cancel
 * hooks run on the calling thread, outside the gate's lock.
 */
public final class ActivityGate {
    private static final Logger logger = Logger.getLogger(ActivityGate.class.getName());

    /**
     * Permission to do one unit of work during the current role period. Closing it is idempotent; a
     * token invalidated by {@link #forceReleaseAll(String)} is already closed.
     */
    public final class Token implements AutoCloseable {
        private final String key;
        private boolean released; // guarded by ActivityGate.this
        private boolean cancelled; // guarded by ActivityGate.this
        private Runnable cancelHook; // guarded by ActivityGate.this

        private Token(String key) {
            this.key = key;
        }

        /** @return the admission key (client IP on the HOST side, a visit id on the VISIT side). */
        public String key() {
            return key;
        }

        /** @return true until the token is closed or force-released. */
        public boolean isValid() {
            synchronized (ActivityGate.this) {
                return !released;
            }
        }

        /** @return true if the drain cap (or a pause/stop) force-released this token. */
        public boolean isCancelled() {
            synchronized (ActivityGate.this) {
                return cancelled;
            }
        }

        /**
         * Register work-abort logic (e.g. cancel a gRPC call, stop a download loop). It runs once, on the
         * thread that calls {@link #forceReleaseAll(String)}, or immediately on this thread if the token
         * was already force-released. Not run on a normal {@link #close()}. Replaces any earlier hook.
         */
        public void setCancelHook(Runnable hook) {
            boolean runNow;
            synchronized (ActivityGate.this) {
                runNow = cancelled;
                if (!runNow) cancelHook = hook;
            }
            if (runNow && hook != null) runSafely(hook, "cancel hook");
        }

        /** Release the token. Idempotent; a no-op after a force release. */
        @Override
        public void close() {
            release(this);
        }

        @Override
        public String toString() {
            return "Token{" + key + (released ? ", released" : "") + (cancelled ? ", cancelled" : "") + "}";
        }
    }

    // All fields guarded by "this".
    private final Map<String, Set<Token>> live = new HashMap<>();
    private int activeCount;
    private boolean open;
    private Runnable drainListener;
    private Runnable admissionListener;

    /** Create a gate with admissions closed (the controller opens it when a role starts). */
    public ActivityGate() {
    }

    /**
     * Try to start a unit of work.
     *
     * <p>While admissions are open, always admits. While closed (draining or between roles), admits only
     * if {@code key} already holds a live token: follow-on RPCs of an admitted client session must not
     * be cut off (see class docs). Each successful call returns a <em>new</em> token that must be closed.
     *
     * @param key admission key (non-null)
     * @return a new token, or null if the work must be rejected (the RPC layer maps this to
     *         {@code Status.UNAVAILABLE})
     */
    public Token tryAdmit(String key) {
        if (key == null) throw new NullPointerException("key");
        Token t;
        Runnable fire;
        synchronized (this) {
            Set<Token> forKey = live.get(key);
            if (!open && (forKey == null || forKey.isEmpty())) return null;
            if (forKey == null) {
                forKey = new LinkedHashSet<>();
                live.put(key, forKey);
            }
            t = new Token(key);
            forKey.add(t);
            activeCount++;
            fire = admissionListener;
        }
        if (fire != null) runSafely(fire, "admission listener");
        return t;
    }

    /**
     * Stop admitting new keys (dwell expired or role ending). Keys with live tokens can still take
     * follow-on tokens. If nothing is active the drain listener does <em>not</em> fire here; callers
     * check {@link #activeCount()} right after closing.
     */
    public synchronized void closeAdmissions() {
        open = false;
    }

    /** Admit new keys again (a new role period started). */
    public synchronized void openAdmissions() {
        open = true;
    }

    /** @return true if admissions are open. */
    public synchronized boolean isOpen() {
        return open;
    }

    /**
     * @return true when admissions are closed; the visit worker checks this between bundles and stops
     *         after the in-flight one.
     */
    public synchronized boolean isClosing() {
        return !open;
    }

    /** @return number of live tokens (all keys). */
    public synchronized int activeCount() {
        return activeCount;
    }

    /** @return number of distinct keys with a live token (shown as "active sessions" in status). */
    public synchronized int activeKeyCount() {
        return live.size();
    }

    /** @return true if {@code key} currently holds a live token. */
    public synchronized boolean isAdmitted(String key) {
        Set<Token> forKey = live.get(key);
        return forKey != null && !forKey.isEmpty();
    }

    /**
     * Set the listener fired (on the thread closing the last token) each time the live token count
     * drops to zero while admissions are closed. Not fired by {@link #forceReleaseAll(String)}.
     * The controller's listener just re-posts to its executor.
     */
    public synchronized void setDrainListener(Runnable listener) {
        this.drainListener = listener;
    }

    /**
     * Set the listener fired (on the admitting thread, outside the gate's lock) after every successful
     * {@link #tryAdmit(String)}. The controller uses it as a wake-up event: it re-checks its deadlines
     * (an arriving client may be what woke a suspended CPU) and its joined-client grace. Callers of
     * {@code tryAdmit} may hold their own locks ({@code ClientSessionTracker} does), so the listener must
     * not block or take other locks; the controller's listener only posts to its executor.
     */
    public synchronized void setAdmissionListener(Runnable listener) {
        this.admissionListener = listener;
    }

    /**
     * Invalidate every live token (drain cap reached, pause or stop) and run their cancel hooks. Later
     * {@link Token#close()} calls on those tokens are no-ops. Does not change the open/closed state.
     *
     * @param reason logged
     * @return the number of tokens released
     */
    public int forceReleaseAll(String reason) {
        List<Runnable> hooks = new ArrayList<>();
        int n;
        synchronized (this) {
            n = activeCount;
            for (Set<Token> tokens : live.values()) {
                for (Token t : tokens) {
                    t.released = true;
                    t.cancelled = true;
                    if (t.cancelHook != null) hooks.add(t.cancelHook);
                    t.cancelHook = null;
                }
            }
            live.clear();
            activeCount = 0;
        }
        if (n > 0) logger.log(Level.INFO, "Force-released " + n + " active token(s): " + reason);
        for (Runnable h : hooks) runSafely(h, "cancel hook");
        return n;
    }

    private void release(Token t) {
        Runnable fire = null;
        synchronized (this) {
            if (t.released) return;
            t.released = true;
            t.cancelHook = null;
            Set<Token> forKey = live.get(t.key);
            if (forKey != null) {
                forKey.remove(t);
                if (forKey.isEmpty()) live.remove(t.key);
            }
            activeCount--;
            if (activeCount == 0 && !open) fire = drainListener;
        }
        if (fire != null) runSafely(fire, "drain listener");
    }

    private static void runSafely(Runnable r, String what) {
        try {
            r.run();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "ActivityGate " + what + " threw", e);
        }
    }
}
