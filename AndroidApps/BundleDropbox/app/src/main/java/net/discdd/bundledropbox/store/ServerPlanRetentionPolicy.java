package net.discdd.bundledropbox.store;

import net.discdd.dropbox.control.DropboxControl;

/**
 * The dropbox's {@link RetentionPolicy}: a relayed bundle is deleted only when a server retention plan names it,
 * and then always.
 *
 * <p>Why every server order is honoured, whatever its reason: each reason the server can give
 * ({@code RECEIVED_BY_SERVER}, {@code SUPERSEDED}, {@code CLIENT_ACKED}, {@code EXPIRED}) is a monotonic fact, so
 * acting on a stale or replayed plan is still safe (Research/R7 §5, Modules/Control Contract). An unknown reason
 * (decoded as {@code null}) still names a valid deletion. The plan arrives Signal-encrypted to the dropbox's own
 * client identity, so only the dropbox's server can issue it (R7 §5 "Authentication comes free").
 *
 * <p>Nothing else deletes on its own: a bundle the server never names stays until the operator clears it.
 * Stateless and thread-safe.
 */
public final class ServerPlanRetentionPolicy implements RetentionPolicy {
    /** Shared instance; the policy is stateless. */
    public static final ServerPlanRetentionPolicy INSTANCE = new ServerPlanRetentionPolicy();

    private ServerPlanRetentionPolicy() {}

    @Override
    public boolean mayDelete(Direction direction, String bundleId, DropboxControl.Reason reason) {
        return direction != null && bundleId != null;
    }

    @Override
    public String toString() {
        return "ServerPlanRetentionPolicy";
    }
}
