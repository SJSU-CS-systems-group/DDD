package net.discdd.bundledropbox.wifi;

import java.net.InetAddress;
import java.util.function.BooleanSupplier;

/**
 * The carrier exchange the Wi-Fi joiner runs once it has joined a carrier's group.
 *
 * <p>The radio layer only knows how to get the dropbox onto a carrier's network; what happens there
 * (getRecencyBlob, upload of unforwarded upstream bundles, download-all via PSI; Research/R2 §4.3, §8) is
 * {@code visit/TransportVisitClient}. {@code service/VisitCoordinator} implements this interface: it holds one
 * {@code ActivityGate} "visit" token from the start of the join to the end of the exchange, and returns
 * {@code ok() && recencyFetched()} of the visit result, and only if the blob was actually stored.
 *
 * <p>Join hooks (audit m1, {@code join-not-protected-by-gate}): the joiner calls {@link #beginJoin} right
 * before it issues {@code connect} and {@link #endJoin} when an attempt ends, so a dwell that expires
 * mid-join drains instead of discarding the join. The defaults make both hooks no-ops for runners that do
 * not care about the gate (tests, older wiring).
 *
 * <p>Threading: called on a driver worker thread (never the main thread), at most once per joined carrier.
 * It may block for the whole exchange.
 *
 * <p>Cancellation: when the controller calls {@code stopVisit} (dwell expiry after the drain cap, or
 * service stop), {@code stopRequested} starts returning true and the worker thread is interrupted; the
 * carrier group is then removed, which also breaks the gRPC connection. Implementations should check
 * {@code stopRequested} between bundles, finish or abort the in-flight bundle, keep partial work
 * retryable, and return promptly. The driver waits at most {@link WifiTimeouts#RUNNER_EXIT_MS} for it.
 * {@code stopRequested} also turns true (with an interrupt) if Wi-Fi Direct is disabled or the channel is
 * lost mid-exchange; the result is then still reported as {@code visitFinished}.
 */
@FunctionalInterface
public interface VisitRunner {
    /**
     * Called on the main thread right before the joiner issues {@code connect} to a carrier. The runner may
     * reserve whatever must outlive the join (the service takes the visit gate token here).
     *
     * @param carrierId the carrier's TXT {@code transportId}
     * @return false to skip this connect because the visit period is already ending (admissions closed);
     *         the joiner then stays in discovery without joining
     */
    default boolean beginJoin(String carrierId) {
        return true;
    }

    /**
     * Called on the main thread whenever an attempt that called {@link #beginJoin} ends: after a failed
     * join, after leaving the carrier, or when the visit session ends. Must be idempotent and a no-op once
     * {@link #runVisit} has taken over what {@code beginJoin} reserved.
     *
     * @param carrierId the carrier passed to {@link #beginJoin}
     */
    default void endJoin(String carrierId) {
    }

    /**
     * Runs one carrier exchange.
     *
     * @param carrierAddress group-owner address of the carrier (from {@code WifiP2pInfo.groupOwnerAddress},
     *                       normally 192.168.49.1); the carrier's gRPC server is on port 7777
     * @param carrierId      the carrier's TXT {@code transportId} (key for {@code ForwardLedger})
     * @param stopRequested  becomes true when the visit must end
     * @return true if the exchange completed (recency fetch succeeded and transfers finished); reported to
     *         the controller as {@code VisitCallback.visitFinished(gen, success)}
     * @throws Exception any failure; treated as {@code false}
     */
    boolean runVisit(InetAddress carrierAddress, String carrierId, BooleanSupplier stopRequested) throws Exception;
}
