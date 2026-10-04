package net.discdd.bundledropbox.cycle;

/**
 * States of the dropbox role cycle owned by {@code RoleCycleController}.
 *
 * <p>The dropbox alternates between two roles because a phone has a single Wi-Fi Direct interface:
 * HOST (act as a transport toward BundleClients) and VISIT (act as a client toward BundleTransports).
 * Each role goes STARTING (radio preparing, no dwell time consumed) → active dwell → DRAINING
 * (admissions closed, finishing in-flight work) → STOPPING (radio teardown, no dwell time consumed).
 *
 * <pre>
 * STOPPED → (no recency blob? skip HOST) → VISIT_STARTING → VISITING → VISIT_DRAINING → VISIT_STOPPING
 *         → HOST_STARTING → HOSTING → HOST_DRAINING → HOST_STOPPING → VISIT_STARTING → ...
 * any radio failure after bounded retries → UNAVAILABLE → (30 s or Wi-Fi/permission change) → retry
 * stop/pause/shutdown from any state → *_STOPPING → STOPPED
 * </pre>
 */
public enum CycleMode {
    /**
     * Controller not running (stopped or paused). Radio idle, unless a stop issued by the halt failed: the
     * controller then retries that stop before the next start (see {@code RoleCycleController}).
     */
    STOPPED,
    /**
     * Creating the Wi-Fi Direct group, making sure the (always-running) client gRPC server is listening, and
     * advertising DNS-SD.
     */
    HOST_STARTING,
    /** Serving clients; the client dwell timer ({@link CycleConfig#clientPeriodMs()}) is running. */
    HOSTING,
    /**
     * Client dwell expired: advertisement withdrawn, admitted sessions finishing (bounded by the drain cap).
     * New clients are rejected, except during the short joined-client grace at its start.
     */
    HOST_DRAINING,
    /** Clearing local services, ending the HOST role's client sessions and removing the group. */
    HOST_STOPPING,
    /** Starting DNS-SD discovery for {@code _ddd._tcp} carriers. */
    VISIT_STARTING,
    /** Looking for / exchanging with a carrier; the transport dwell timer is running. */
    VISITING,
    /** Transport dwell expired: finishing the in-flight bundle (bounded by the drain cap). */
    VISIT_DRAINING,
    /** Cancelling connect/exchange, leaving the carrier's group and stopping discovery. */
    VISIT_STOPPING,
    /** Radio operations failed repeatedly (or Wi-Fi/permission missing); waiting to retry. */
    UNAVAILABLE;

    /** @return true for the four client-serving (HOST) states. */
    public boolean isHostSide() {
        return this == HOST_STARTING || this == HOSTING || this == HOST_DRAINING || this == HOST_STOPPING;
    }

    /** @return true for the four transport-facing (VISIT) states. */
    public boolean isVisitSide() {
        return this == VISIT_STARTING || this == VISITING || this == VISIT_DRAINING || this == VISIT_STOPPING;
    }

    /** @return true for the states whose dwell timer is running ({@link #HOSTING}, {@link #VISITING}). */
    public boolean isDwelling() {
        return this == HOSTING || this == VISITING;
    }

    /** @return true for the drain states, where the drain-cap deadline applies. */
    public boolean isDraining() {
        return this == HOST_DRAINING || this == VISIT_DRAINING;
    }
}
