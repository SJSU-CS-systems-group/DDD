package net.discdd.bundledropbox.store;

import net.discdd.dropbox.control.DropboxControl;

/**
 * Decides whether a relayed bundle may be removed from the {@link RelayStore} when the DDD server orders it.
 *
 * <p>The dropbox itself cannot tell whether an UPSTREAM bundle reached the server or a DOWNSTREAM bundle reached
 * its client: carriers do not report back, and a client's ack travels inside its next encrypted upstream bundle,
 * which only the server can read (Research/R3 §6, R4 §4.3). So nothing in the relay path deletes on its own.
 * Storage is managed by the server instead: the dropbox reports its holdings in inventory ADUs and the server
 * answers with retention plans naming exactly which bundles to delete (06 Storage Management Design). The
 * {@code control/PlanExecutor} asks this policy before acting on each listed bundle.
 *
 * <p>The operator's "clear" buttons on the Storage tab are a manual override and do not consult the policy.
 *
 * <p>Implemented by {@link ServerPlanRetentionPolicy}. Implementations must be thread-safe.
 */
public interface RetentionPolicy {
    /**
     * @param direction the queue the server named
     * @param bundleId  encrypted bundle id the server named
     * @param reason    the server's reason, or {@code null} when the plan used a reason this build does not know
     *                  (the contract keeps such entries valid: reasons are informational)
     * @return true if the dropbox should delete the bundle now
     */
    boolean mayDelete(Direction direction, String bundleId, DropboxControl.Reason reason);
}
