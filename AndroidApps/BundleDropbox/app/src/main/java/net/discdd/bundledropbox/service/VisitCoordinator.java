package net.discdd.bundledropbox.service;

import net.discdd.bundledropbox.cycle.ActivityGate;
import net.discdd.bundledropbox.visit.TransportVisitClient;
import net.discdd.bundledropbox.visit.VisitResult;
import net.discdd.bundledropbox.wifi.VisitRunner;

import java.net.InetAddress;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The service's {@link VisitRunner}: ties one carrier exchange to the role cycle's {@link ActivityGate}.
 * Extracted from {@code DropboxService.runVisit} so the rules below are JVM-tested (audit m19,
 * {@code service-glue-untested}).
 *
 * <h2>Gate token ("visit" key)</h2>
 * One token covers the whole exchange (03 Architecture "Visit-side keying"), so a VISIT dwell that expires
 * mid-exchange drains instead of cutting the transfer: {@code keepGoing} turns false when admissions close,
 * the visit client finishes the in-flight bundle and returns, the token closes and the controller switches.
 * If the drain cap passes first, the gate force-releases the token and its cancel hook hard-cancels the gRPC
 * channel; partial downloads are aborted and nothing is marked forwarded without the carrier's ack.
 *
 * <h2>Token from the start of the join (audit m1, {@code join-not-protected-by-gate})</h2>
 * A Wi-Fi Direct join takes up to {@code WifiTimeouts.CONNECT_MS} (20 s). If the token were only taken when
 * the exchange starts, a dwell that expires mid-join would see no activity, stop at once and waste the join.
 * So the joiner calls {@link #beginJoin} right before it issues {@code connect}, which takes the token then
 * (and refuses the connect if admissions are already closed), {@link #runVisit} reuses that token, and
 * {@link #endJoin} releases it if the attempt ends without an exchange. A join caught by the dwell end then
 * gets the drain grace (bounded by the drain cap) to finish cleanly instead of being torn down mid-join. If no
 * {@code beginJoin} preceded the exchange, {@link #runVisit} takes the token itself.
 *
 * <h2>Early return</h2>
 * {@link #returnEarly} decides the {@code success} flag reported to the controller (which ends VISIT early
 * when {@code return_after_visit} is on). See that method for the bootstrap rule (Review #9).
 *
 * <p>Thread-safety: {@link #beginJoin}/{@link #endJoin} run on the main thread, {@link #runVisit} on a driver
 * worker; the pending join token is guarded by {@code this}. Gate tokens are closed outside that lock because
 * closing may fire the gate's drain listener.
 */
public final class VisitCoordinator implements VisitRunner {
    /** ActivityGate key of the visit token (client sessions are keyed by IP, so no clash). */
    public static final String VISIT_GATE_KEY = "visit";

    /** The carrier exchange; {@link TransportVisitClient} in production ({@link #exchangeOf}). */
    public interface Exchange {
        /** See {@link TransportVisitClient#visit(InetAddress, int, String, BooleanSupplier, BooleanSupplier)}. */
        VisitResult visit(InetAddress host, int port, String carrierId, BooleanSupplier keepGoing,
                          BooleanSupplier cancelled);

        /** Hard-cancels the visit in progress (any thread). */
        void cancel();
    }

    /** Service-side reactions (status, logs). Called on the worker thread; must not block for long. */
    public interface Events {
        /** The exchange is about to start (the token is held). */
        void exchangeStarting(String carrierId);

        /** The exchange returned this result. */
        void visitResult(VisitResult result);

        /** The exchange's token was released (always paired with {@link #exchangeStarting}). */
        void exchangeEnded();

        /** No exchange was attempted with this joined carrier, e.g. the dwell ended first. */
        void visitSkipped(String carrierId, String why);
    }

    private final Supplier<ActivityGate> gate;
    private final Supplier<Exchange> exchange;
    private final BooleanSupplier ownBlobHeld;
    private final Events events;

    private ActivityGate.Token joinToken; // guarded by this
    private String joinCarrierId; // guarded by this

    /**
     * @param gate       the cycle's gate; read lazily because the driver (which holds this runner) is built
     *                   before the controller. {@code null} means "not wired": every visit is skipped
     * @param exchange   the visit client; read lazily for the same reason
     * @param ownBlobHeld true while the dropbox holds its own server-issued recency blob
     *                    ({@code recency.currentTimestamp() > 0}), i.e. while it may HOST
     * @param events     status/log reactions
     */
    public VisitCoordinator(Supplier<ActivityGate> gate, Supplier<Exchange> exchange, BooleanSupplier ownBlobHeld,
                            Events events) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        this.ownBlobHeld = Objects.requireNonNull(ownBlobHeld, "ownBlobHeld");
        this.events = Objects.requireNonNull(events, "events");
    }

    /** Adapts the production visit client. */
    public static Exchange exchangeOf(TransportVisitClient client) {
        return new Exchange() {
            @Override
            public VisitResult visit(InetAddress host, int port, String carrierId, BooleanSupplier keepGoing,
                                     BooleanSupplier cancelled) {
                return client.visit(host, port, carrierId, keepGoing, cancelled);
            }

            @Override
            public void cancel() {
                client.cancel();
            }
        };
    }

    /**
     * Joiner hook: call right before issuing {@code connect} to a carrier. Takes the visit token now so a
     * dwell that expires during the join drains (bounded by the drain cap) instead of stopping at once.
     *
     * @param carrierId the carrier about to be joined
     * @return false if admissions are closed (the dwell already ended): do not start the connect
     */
    @Override
    public boolean beginJoin(String carrierId) {
        ActivityGate g = gate.get();
        // Release a leftover token (an attempt that never called endJoin) BEFORE admitting: while any "visit"
        // token is live the gate admits follow-on tokens for the key even with admissions closed, which would
        // let a new join start after the dwell ended.
        ActivityGate.Token stale = takeJoinToken(null);
        if (stale != null) stale.close();
        ActivityGate.Token taken = g == null ? null : g.tryAdmit(VISIT_GATE_KEY);
        ActivityGate.Token displaced;
        synchronized (this) {
            displaced = joinToken; // only if beginJoin raced with itself; the joiner calls it on main only
            joinToken = taken;
            joinCarrierId = taken == null ? null : carrierId;
        }
        if (displaced != null) displaced.close();
        return taken != null;
    }

    /**
     * Joiner hook: the attempt that called {@link #beginJoin} ended without {@link #runVisit} (connect failed,
     * timed out, wrong group, P2P disabled). Releases its token, which may complete a pending drain at once.
     * A no-op if {@link #runVisit} already took the token, so it is safe to call on every attempt end.
     */
    @Override
    public void endJoin(String carrierId) {
        ActivityGate.Token t = takeJoinToken(carrierId);
        if (t != null) t.close();
    }

    @Override
    public boolean runVisit(InetAddress carrierAddress, String carrierId, BooleanSupplier stopRequested) {
        ActivityGate g = gate.get();
        Exchange ex = exchange.get();
        // The joiner runs one attempt at a time, so a pending join token belongs to this exchange (same key).
        ActivityGate.Token token = takeJoinToken(null);
        if (g == null || ex == null) {
            if (token != null) token.close();
            events.visitSkipped(carrierId, "dropbox not initialized");
            return false;
        }
        if (token == null) token = g.tryAdmit(VISIT_GATE_KEY);
        if (token == null) {
            events.visitSkipped(carrierId, "the transport period ended before the exchange");
            return false;
        }
        if (!token.isValid()) {
            // Force-released during the join (drain cap, pause, stop): the controller is stopping the visit.
            events.visitSkipped(carrierId, "cancelled while joining");
            return false;
        }
        token.setCancelHook(ex::cancel);
        try {
            events.exchangeStarting(carrierId);
            // token::isCancelled: a drain-cap force release that lands before visit() starts must not be lost
            // (the hook's cancel() alone would be reset when the visit begins).
            VisitResult r = ex.visit(carrierAddress,
                                     TransportVisitClient.TRANSPORT_PORT,
                                     carrierId,
                                     () -> !stopRequested.getAsBoolean() && !g.isClosing(),
                                     token::isCancelled);
            events.visitResult(r);
            return returnEarly(r, ownBlobHeld.getAsBoolean());
        } finally {
            token.close(); // may fire the drain listener: VISIT_DRAINING ends now
            events.exchangeEnded();
        }
    }

    /**
     * The {@code success} flag for {@code VisitCallback.visitFinished}, which ends VISIT early when
     * {@code return_after_visit} is on. True if the exchange completed and the dropbox holds its own recency
     * blob now. Whether the carrier had a blob of its own does not matter: the dropbox never uses it
     * (09 Own Recency Blob Design).
     *
     * <p>The held-blob condition is the bootstrap rule, not a property of the visit: without a blob HOST is
     * skipped, so an early return would only restart VISIT with the selector's marks cleared and rejoin the same
     * carrier over and over (Review #9, audit i2). During bootstrap the dropbox therefore stays in VISIT for the
     * whole transport period; its recency request rides in the own bundle the visit already uploaded.
     */
    static boolean returnEarly(VisitResult r, boolean ownBlobHeldNow) {
        return r.ok() && ownBlobHeldNow;
    }

    /** @return true while a join token taken by {@link #beginJoin} is pending (tests). */
    synchronized boolean hasPendingJoin() {
        return joinToken != null;
    }

    /** @param carrierId only take the token of this carrier, or {@code null} for any pending token */
    private synchronized ActivityGate.Token takeJoinToken(String carrierId) {
        if (joinToken == null || (carrierId != null && !carrierId.equals(joinCarrierId))) return null;
        ActivityGate.Token t = joinToken;
        joinToken = null;
        joinCarrierId = null;
        return t;
    }
}
