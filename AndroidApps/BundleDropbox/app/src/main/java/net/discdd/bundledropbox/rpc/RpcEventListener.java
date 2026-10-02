package net.discdd.bundledropbox.rpc;

import net.discdd.bundledropbox.store.BundleRecord;
import net.discdd.bundlerouting.service.BundleExchangeServiceImpl.BundleExchangeEvent;

/**
 * Status-relevant events from the client-facing gRPC server ({@link DropboxRpcServer}).
 *
 * <p>The service layer ({@code service/DropboxService}) implements this to refresh
 * {@code DropboxStatusRepository} (active sessions, store counts) and to write user-visible log lines.
 * The rpc package itself never touches Android or the status repository, so it stays JVM-testable.
 *
 * <p>Threading: callbacks arrive on gRPC executor threads (or the session reaper thread for
 * {@link #onActiveSessionsChanged}), possibly concurrently. Implementations must be thread-safe and fast;
 * never block or call back into the server from a callback. Every method has a no-op default.
 */
public interface RpcEventListener {

    /** Listener that ignores every event. */
    RpcEventListener NO_OP = new RpcEventListener() {};

    /**
     * The number of admitted client sessions changed (a client was admitted, finished its upload, went
     * idle, or all sessions were dropped on {@link DropboxRpcServer#stop()}).
     *
     * @param activeSessions current number of open sessions (each holds one ActivityGate token)
     */
    default void onActiveSessionsChanged(int activeSessions) {}

    /**
     * A new client was refused because admissions are closed (the cycle is draining or not hosting).
     * The client got {@code UNAVAILABLE} and will retry on its own schedule.
     *
     * @param remoteIp   the refused client's IP
     * @param fullMethod the gRPC method it tried to call
     */
    default void onClientRejected(String remoteIp, String fullMethod) {}

    /**
     * A client upload was fully received and committed to the UPSTREAM queue.
     *
     * @param record the committed bundle
     */
    default void onUploadCommitted(BundleRecord record) {}

    /**
     * A client upload was refused or discarded (store full, unclean id, stream error, commit failure).
     * Any staged bytes have already been aborted.
     *
     * @param claimedBundleId the id the client sent, or {@code null} if it never sent one
     * @param reason          short human-readable reason
     */
    default void onUploadFailed(String claimedBundleId, String reason) {}

    /**
     * A DOWNSTREAM bundle was streamed to a client to completion (PSI or legacy download).
     * Also recorded in {@code DeliveryLog}.
     *
     * @param bundleId encrypted bundle id that was served
     */
    default void onDownloadServed(String bundleId) {}

    /**
     * Raw bundle-core exchange events, forwarded unchanged. They are NOT balanced on error paths
     * (Research/R1 §3.2, §3.5), so use them only for logging/animation, never for busy accounting; the
     * session tracker is the authoritative busy signal.
     *
     * @param event the bundle-core event
     */
    default void onExchangeEvent(BundleExchangeEvent event) {}
}
