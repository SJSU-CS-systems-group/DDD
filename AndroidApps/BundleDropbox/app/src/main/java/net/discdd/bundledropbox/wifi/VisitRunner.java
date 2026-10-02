package net.discdd.bundledropbox.wifi;

import java.net.InetAddress;
import java.util.function.BooleanSupplier;

/**
 * The carrier exchange the Wi-Fi joiner runs once it has joined a carrier's group.
 *
 * <p>The radio layer only knows how to get the dropbox onto a carrier's network; what happens there
 * (getRecencyBlob, upload of unforwarded upstream bundles, download-all via PSI; Research/R2 §4.3, §8) is
 * the integrator's {@code visit/TransportVisitClient}. The integrator implements this interface, typically
 * as {@code (addr, carrierId, stop) -> transportVisitClient.visit(addr, 7777, carrierId, stop).ok()},
 * holding one {@code ActivityGate} token for the whole call.
 *
 * <p>Threading: called on a driver worker thread (never the main thread), at most once per joined carrier.
 * It may block for the whole exchange.
 *
 * <p>Cancellation: when the controller calls {@code stopVisit} (dwell expiry after the drain cap, or
 * service stop), {@code stopRequested} starts returning true and the worker thread is interrupted; the
 * carrier group is then removed, which also breaks the gRPC connection. Implementations should check
 * {@code stopRequested} between bundles, finish or abort the in-flight bundle, keep partial work
 * retryable, and return promptly. The driver waits at most {@link WifiTimeouts#RUNNER_EXIT_MS} for it.
 */
@FunctionalInterface
public interface VisitRunner {
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
