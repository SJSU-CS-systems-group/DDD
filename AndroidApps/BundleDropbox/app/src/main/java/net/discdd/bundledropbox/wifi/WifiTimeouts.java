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

    /** Interval between {@code requestGroupInfo}/{@code requestConnectionInfo} polls. */
    public static final long POLL_INTERVAL_MS = 500L;
    /**
     * Re-issue {@code discoverServices} this often while idle in VISIT: Android stops a service
     * discovery on its own after a while, and a fresh request also re-queries carriers that appeared late.
     */
    public static final long REDISCOVER_INTERVAL_MS = 12_000L;

    private WifiTimeouts() {
    }
}
