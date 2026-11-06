package net.discdd.bundledropbox.wifi;

/**
 * Edge detector for the radio's availability ("Wi-Fi Direct usable": manager present, channel up, P2P
 * enabled, NEARBY_WIFI_DEVICES granted), used by {@link WifiDirectRadioDriver} to decide when to call
 * {@link RadioEnvironmentListener#onRadioAvailabilityChanged}.
 *
 * <p>Only real environment observations may be fed in. Role-loss signals (the hosted group vanished while
 * P2P stayed on) are reported separately through {@link RadioEnvironmentListener#onRoleLost} and must not
 * touch this state: before audit findings m3/m10 they were stored here as "unavailable", which hid the next
 * real "Wi-Fi Direct disabled" edge (the cycle kept VISITING with the radio off) and, after a channel loss,
 * the "available again" edge that lets the cycle leave UNAVAILABLE early.
 *
 * <p>Pure Java; main-thread use only (not synchronized).
 */
final class AvailabilityEdges {
    /**
     * Last observed availability. Starts false so the first "usable" observation after initialize is
     * reported (the cycle treats it as a retryNow, a no-op unless UNAVAILABLE), while an initial
     * "unusable" observation is not (nothing is running yet that could be lost).
     */
    private boolean lastAvailable;

    /**
     * Records an observation of the real environment.
     *
     * @param available whether the radio is usable right now
     * @return true if this differs from the previous observation, i.e. an edge the listener should hear
     */
    boolean observe(boolean available) {
        boolean edge = available != lastAvailable;
        lastAvailable = available;
        return edge;
    }

    /** What the driver tells its {@link RadioEnvironmentListener} after one observation. */
    enum Report {
        /** Nothing: no edge, and no established role ended. */
        NONE,
        /** An availability edge: {@link RadioEnvironmentListener#onRadioAvailabilityChanged}. */
        EDGE,
        /**
         * No edge (the state already said "unavailable"), but an established role just ended:
         * {@link RadioEnvironmentListener#onRoleLost}, so the cycle never keeps a role on a dead radio.
         */
        ROLE_LOST
    }

    /**
     * Records an observation of the real environment and decides what to report (audit m3/m10). An edge
     * wins: the service maps an "unavailable" edge to roleLost itself, so role loss is reported separately
     * only when there is no edge (audit CL-7: one decision for every driver path).
     *
     * @param available whether the radio is usable right now
     * @param lostRole  true if this observation just ended an established HOSTING or VISIT role
     * @return the report the listener should hear
     */
    Report report(boolean available, boolean lostRole) {
        if (observe(available)) return Report.EDGE;
        return lostRole ? Report.ROLE_LOST : Report.NONE;
    }

    /** @return the last observed availability (visible for tests) */
    boolean lastAvailable() {
        return lastAvailable;
    }
}
