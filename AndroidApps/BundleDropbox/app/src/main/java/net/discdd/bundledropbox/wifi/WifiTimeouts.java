package net.discdd.bundledropbox.wifi;

/**
 * Upper bounds for every asynchronous Wi-Fi Direct step. No step may wait forever: if the framework
 * never calls back (which happens on some vendor builds), the step fails with a "timed out" reason and
 * the controller's retry/UNAVAILABLE logic takes over.
 *
 * <p>Values are the task's frozen choices; the BundleClient uses 10 s for its whole connect+address wait
 * (BundleClientService.java:337), we allow longer because the dropbox has nobody waiting on a UI.
 */
public final class WifiTimeouts {
    /** {@code connect} request until the carrier's group-owner address is known. */
    public static final long CONNECT_MS = 20_000L;
    /** {@code createGroup} request until we are observed as group owner. */
    public static final long GROUP_CREATE_MS = 15_000L;
    /** {@code removeGroup} request until {@code requestGroupInfo} returns null. */
    public static final long REMOVAL_MS = 10_000L;
    /** Service-level requests: add/clear local services, add/clear service requests, discoverServices. */
    public static final long SERVICE_OP_MS = 10_000L;
    /** {@link HostingEndpoint#start()} / {@link HostingEndpoint#stop()} on the worker thread. */
    public static final long ENDPOINT_MS = 10_000L;
    /** How long stopVisit waits for a cancelled {@link VisitRunner} to return before reporting idle. */
    public static final long RUNNER_EXIT_MS = 5_000L;

    /** Interval between {@code requestGroupInfo} polls (group formed / group removed). */
    public static final long POLL_INTERVAL_MS = 500L;
    /**
     * Interval between {@code requestConnectionInfo} polls while joining a carrier. Slower than
     * {@link #POLL_INTERVAL_MS} on purpose: this is only the fallback for a missed
     * {@code WIFI_P2P_CONNECTION_CHANGED_ACTION} (Research/R2 §1.3 item 4), GO negotiation plus DHCP take
     * seconds anyway, and extra requests during negotiation only add load on a framework that is already
     * answering BUSY. Bounded overall by {@link #CONNECT_MS}.
     */
    public static final long CONNECTION_POLL_MS = 1_000L;
    /**
     * Backoff for re-creating the P2P channel after the framework dropped it (first retry after 1 s, doubling
     * up to 30 s, the controller's UNAVAILABLE retry interval). Re-creating it lets the driver report
     * "available" again, so the cycle can leave UNAVAILABLE at once (audit finding m10).
     */
    public static final RetryBackoff CHANNEL_RECOVERY = new RetryBackoff(1_000L, 30_000L, Integer.MAX_VALUE);
    /**
     * Re-issue {@code discoverServices} this often while idle in VISIT: Android stops a service
     * discovery on its own after a while, and a fresh request also re-queries carriers that appeared late.
     */
    public static final long REDISCOVER_INTERVAL_MS = 12_000L;

    /**
     * Worst case of one {@code P2pLink.removeGroupAndAwaitGone}: group probe, removeGroup and the removal
     * poll share the {@link #REMOVAL_MS} budget, and the final poll can overrun it by one interval plus its
     * watchdog interval (the probe itself is bounded by two intervals inside the budget).
     */
    public static final long REMOVAL_WORST_MS = REMOVAL_MS + 2 * POLL_INTERVAL_MS;
    /**
     * Worst case of stopVisit: up to three best-effort steps (cancelConnect when a connect may be pending,
     * stopPeerDiscovery, clearServiceRequests), each ended by its {@link #SERVICE_OP_MS} timeout however
     * often it is BUSY-retried, then the group removal, then the wait for a cancelled runner.
     */
    public static final long VISIT_STOP_WORST_MS = 3 * SERVICE_OP_MS + REMOVAL_WORST_MS + RUNNER_EXIT_MS;
    /** Worst case of stopHosting: clearLocalServices, the endpoint stop, then the group removal. */
    public static final long HOST_STOP_WORST_MS = SERVICE_OP_MS + ENDPOINT_MS + REMOVAL_WORST_MS;
    /**
     * Upper bound on any role stop, for service teardown waiting on the driver to be idle (audit WD-2): the
     * longer of the two stops plus one poll interval for main-thread posting and the runner-exit check
     * (100 ms granularity). 46.5 s with the values above; normal stops take a second or two.
     */
    public static final long STOP_WORST_MS = Math.max(VISIT_STOP_WORST_MS, HOST_STOP_WORST_MS) + POLL_INTERVAL_MS;

    private WifiTimeouts() {
    }
}
