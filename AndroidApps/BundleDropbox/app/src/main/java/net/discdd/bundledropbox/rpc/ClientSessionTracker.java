package net.discdd.bundledropbox.rpc;

import net.discdd.bundledropbox.cycle.ActivityGate;
import net.discdd.bundledropbox.cycle.Clock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Groups the independent RPCs of a stock BundleClient exchange into one "client session" and ties that
 * session to the role cycle's {@link ActivityGate}, so the HOST dwell timer can "finish the in-flight
 * bundle, then switch" (02 Decisions, flexible timer).
 *
 * <p>Session model (03 Architecture, "Admission and drain"):
 * <ul>
 *   <li>Key = client remote IP ({@link RemoteAddrInterceptor}). The stock client has no session id.</li>
 *   <li>The first RPC from an IP calls {@link ActivityGate#tryAdmit(String)}. A {@code null} token means
 *       admissions are closed (draining / not hosting): the RPC is refused and the interceptor answers
 *       {@code UNAVAILABLE}. This is NOT a harmless "try later" for the stock client: it stamps
 *       {@code lastExchange} for our advertised transportId even though the exchange failed, and then skips
 *       us until the advertised id rotates or a newer recency blob is advertised (R3 §7). That is why the
 *       cycle withdraws the DNS-SD record as soon as a HOST drain begins, and why sessions are never reaped
 *       while draining (below).</li>
 *   <li>Later RPCs from an admitted IP reuse the session's token, so they pass even while draining:
 *       a client that already fetched the recency blob must be allowed to finish PSI and its upload.</li>
 *   <li>The session ends (token closed) when {@code uploadBundle} finishes, because upload is the last RPC
 *       of a stock client exchange (Research/R1 §3: getRecencyBlob, psiExchange, psiDownloadBundle,
 *       uploadBundle), or after {@link #DEFAULT_IDLE_TIMEOUT_MS} with no RPC in flight (client crashed,
 *       aborted after a RecencyException, or had nothing to upload).</li>
 *   <li>Idle sessions are reaped only while admissions are OPEN. Between {@code psiDownloadBundle} and
 *       {@code uploadBundle} the stock client does long local work with nothing in flight
 *       ({@code clientFindMatches}, {@code processReceivedBundle}, {@code generateBundleForTransmission});
 *       reaping it during a drain would make its upload a "new" client and get it refused, losing the most
 *       valuable direction and locking the client out. While draining, the drain cap bounds a dead session
 *       instead.</li>
 *   <li>If the cycle hits its drain cap it calls {@link ActivityGate#forceReleaseAll(String)}; the token's
 *       cancel hook drops the session here so the next RPC from that IP must be re-admitted (and is
 *       refused). The server keeps running across roles, so RPCs still in flight are not cut here: they die
 *       when {@code DropboxWifiHost.stop} removes the group (uploads are then aborted from staging, never
 *       committed truncated). {@link DropboxRpcServer#stop()} ({@code shutdownNow}) only runs at service
 *       teardown.</li>
 * </ul>
 *
 * <p>Why not use bundle-core's {@code BundleExchangeEvent}s for busy detection: they are unbalanced on
 * error paths and {@code getRecencyBlob} emits none (Research/R1 §3.2, §7). The interceptor's
 * {@code onComplete}/{@code onCancel} pair is always balanced, so in-flight counts here are exact.
 *
 * <p>Threading: all methods are {@code synchronized}; gate calls that can fire listeners
 * ({@link ActivityGate.Token#close()}) and {@link RpcEventListener} callbacks are made outside the lock.
 * {@link #reapIdle()} is driven by a periodic task owned by {@link DropboxRpcServer} (or directly by tests).
 */
public final class ClientSessionTracker {
    private static final Logger logger = Logger.getLogger(ClientSessionTracker.class.getName());

    /** A session with nothing in flight for this long is ended (03 Architecture: 10 s). */
    public static final long DEFAULT_IDLE_TIMEOUT_MS = 10_000;

    private final ActivityGate gate;
    private final Clock clock;
    private final long idleTimeoutMs;
    private final RpcEventListener listener;

    // guarded by this
    private final Map<String, Session> sessions = new HashMap<>();

    /**
     * @param gate          the cycle's gate ({@code RoleCycleController.gate()})
     * @param clock         monotonic clock (the cycle's {@code Clock}; {@code SystemClock::elapsedRealtime})
     * @param idleTimeoutMs idle time after which a session with no in-flight RPC ends
     * @param listener      receives {@link RpcEventListener#onActiveSessionsChanged(int)}; may be
     *                      {@link RpcEventListener#NO_OP}
     */
    public ClientSessionTracker(ActivityGate gate, Clock clock, long idleTimeoutMs, RpcEventListener listener) {
        if (gate == null || clock == null) throw new NullPointerException("gate/clock");
        if (idleTimeoutMs <= 0) throw new IllegalArgumentException("idleTimeoutMs must be > 0");
        this.gate = gate;
        this.clock = clock;
        this.idleTimeoutMs = idleTimeoutMs;
        this.listener = listener == null ? RpcEventListener.NO_OP : listener;
    }

    /**
     * Admission check for one RPC. Call exactly once per RPC, before the handler runs, and pair every
     * non-null result with exactly one {@link RpcTicket#end(boolean)}.
     *
     * @param ip the client's remote IP (session key)
     * @return a ticket for this RPC, or {@code null} if the client must be refused with UNAVAILABLE
     */
    public RpcTicket beginRpc(String ip) {
        Session s;
        boolean created = false;
        int count;
        synchronized (this) {
            s = sessions.get(ip);
            if (s == null) {
                // New client: ask the gate. Returns null while admissions are closed (draining).
                ActivityGate.Token token = gate.tryAdmit(ip);
                if (token == null) {
                    return null;
                }
                s = new Session(ip, token);
                sessions.put(ip, s);
                created = true;
            }
            s.inFlight++;
            s.lastActivityMs = clock.nowMillis();
            count = sessions.size();
        }
        if (created) {
            final Session admitted = s;
            // Drain cap reached (or cycle paused/stopped): forget the session so the IP is re-admitted
            // (and therefore refused while closed) on its next RPC. Runs on the cycle thread.
            admitted.token.setCancelHook(() -> onForceReleased(admitted));
            logger.log(Level.INFO, "Client session admitted: " + ip);
            listener.onActiveSessionsChanged(count);
        }
        return new RpcTicket(s);
    }

    /**
     * Ends idle sessions: those with no RPC in flight whose last activity is at least the idle timeout
     * ago. Called periodically (about once a second) by {@link DropboxRpcServer}. Does nothing while
     * admissions are closed (see class docs: a reaped client could not be re-admitted during the drain).
     *
     * @return the number of sessions ended
     */
    public int reapIdle() {
        List<Session> ended = new ArrayList<>();
        int count;
        if (!gate.isOpen()) return 0; // draining: the drain cap, not the idle timer, bounds dead sessions
        synchronized (this) {
            long now = clock.nowMillis();
            for (Session s : new ArrayList<>(sessions.values())) {
                if (s.inFlight == 0 && now - s.lastActivityMs >= idleTimeoutMs) {
                    sessions.remove(s.ip);
                    ended.add(s);
                }
            }
            count = sessions.size();
        }
        finish(ended, count, "idle");
        return ended.size();
    }

    /**
     * Ends every session and releases its token (server stopping). In-flight RPCs keep their tickets;
     * ending them later is harmless.
     */
    public void closeAll() {
        List<Session> ended;
        synchronized (this) {
            ended = new ArrayList<>(sessions.values());
            sessions.clear();
        }
        finish(ended, 0, "server stopping");
    }

    /** @return number of currently admitted client sessions. */
    public synchronized int activeSessions() {
        return sessions.size();
    }

    /** @return true if {@code ip} currently has an admitted session. */
    public synchronized boolean hasSession(String ip) {
        return sessions.containsKey(ip);
    }

    private void endRpc(Session s, boolean endsSession) {
        boolean removed = false;
        int count;
        synchronized (this) {
            s.inFlight = Math.max(0, s.inFlight - 1);
            s.lastActivityMs = clock.nowMillis();
            // uploadBundle is the client's last RPC. Only end once nothing else from this IP is in
            // flight; otherwise the idle reaper ends it after the remaining RPCs finish.
            if (endsSession && s.inFlight == 0 && sessions.get(s.ip) == s) {
                sessions.remove(s.ip);
                removed = true;
            }
            count = sessions.size();
        }
        if (removed) {
            finish(List.of(s), count, "upload finished");
        }
    }

    private void onForceReleased(Session s) {
        boolean removed;
        int count;
        synchronized (this) {
            removed = sessions.get(s.ip) == s;
            if (removed) sessions.remove(s.ip);
            count = sessions.size();
        }
        if (removed) {
            logger.log(Level.INFO, "Client session force-released by drain cap: " + s.ip);
            listener.onActiveSessionsChanged(count);
        }
    }

    private void finish(List<Session> ended, int count, String why) {
        if (ended.isEmpty()) return;
        for (Session s : ended) {
            logger.log(Level.INFO, "Client session ended (" + why + "): " + s.ip);
            // May fire the gate's drain listener (last token while closed); must be outside our lock.
            s.token.close();
        }
        listener.onActiveSessionsChanged(count);
    }

    /** One admitted client (one IP) and the gate token it holds. Fields guarded by the tracker. */
    private static final class Session {
        final String ip;
        final ActivityGate.Token token;
        int inFlight;
        long lastActivityMs;

        Session(String ip, ActivityGate.Token token) {
            this.ip = ip;
            this.token = token;
        }
    }

    /** Handle for one admitted RPC. {@link #end(boolean)} is idempotent. */
    public final class RpcTicket {
        private final Session session;
        private boolean ended; // guarded by ClientSessionTracker.this

        private RpcTicket(Session session) {
            this.session = session;
        }

        /** @return the client IP this RPC belongs to. */
        public String ip() {
            return session.ip;
        }

        /**
         * Marks this RPC as finished (completed or cancelled).
         *
         * @param endsSession true for {@code uploadBundle}, the last RPC of a stock client exchange
         */
        public void end(boolean endsSession) {
            synchronized (ClientSessionTracker.this) {
                if (ended) return;
                ended = true;
            }
            endRpc(session, endsSession);
        }
    }
}
