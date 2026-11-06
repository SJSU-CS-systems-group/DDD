package net.discdd.bundledropbox.wifi;

/**
 * Pure-Java decisions about which optional Wi-Fi Direct requests {@link P2pLink} and {@link DropboxWifiJoiner}
 * send, so they are JVM-testable without the framework.
 *
 * <p>Why skip requests at all (audit WD-1): on AOSP a request the P2P state machine does not handle in its
 * current state (removeGroup with no group, cancelConnect outside group formation, any request while P2P is
 * disabled) is answered BUSY, not ERROR, and {@link P2pLink#action} retries BUSY for about 7.75 s
 * ({@link RetryBackoff#BUSY}). That is framework source as read in the audit, not yet confirmed in a device
 * log (the FINE "BUSY, retry" line shows it). Not sending such a request is what keeps VISIT start/stop and
 * carrier leave short.
 */
final class RadioSteps {
    /** What the {@code requestGroupInfo} probe before a group removal saw. */
    enum GroupProbe {
        /** Answered null: this device is in no group. */
        NO_GROUP,
        /** Answered with a group (ours as GO, or a carrier's as client). */
        GROUP,
        /** No usable answer (timeout, SecurityException, lost channel): the group state is unknown. */
        NO_ANSWER
    }

    private RadioSteps() {
    }

    /**
     * @param probe result of the group probe
     * @return true if {@code removeGroup} must be sent; false only when the probe saw no group, so the
     *         removal is already done. An unknown state still sends it (the old, always-send behaviour).
     */
    static boolean needsRemoveGroup(GroupProbe probe) {
        return needsRemoveGroup(probe, false);
    }

    /**
     * @param probe         result of the group probe
     * @param groupMayForm  a {@code createGroup} we sent may still be forming its group: AOSP's
     *                      {@code requestGroupInfo} answers null from the request until the group starts, so a
     *                      NO_GROUP probe then proves nothing, and removeGroup (whose BUSY retry waits for the
     *                      group to exist) is sent as before WD-1
     * @return true if {@code removeGroup} must be sent
     */
    static boolean needsRemoveGroup(GroupProbe probe, boolean groupMayForm) {
        return probe != GroupProbe.NO_GROUP || groupMayForm;
    }

    /**
     * Leaving an attempt whose connect may still be pending: whether to send {@code cancelConnect} first.
     *
     * @param groupVisible a {@code requestGroupInfo} probe just answered with a group
     * @return false when a group exists (the join got past group formation, so cancelConnect would only be
     *         answered BUSY and retried; removeGroup leaves it); true otherwise, including no answer
     */
    static boolean needsCancelConnect(boolean groupVisible) {
        return !groupVisible;
    }

    /**
     * Whether a {@code discoverServices} request (and its BUSY retries) is still wanted (audit WD-4). Before
     * the visit reported ready, discovery is the start step itself. After that it is wanted only while the
     * joiner is idle in DISCOVERING: once a connect has started, a find can break GO negotiation or disturb
     * the exchange link, and leaving the carrier re-issues discovery anyway.
     *
     * @param sessionLive     the visit session is still the active one
     * @param readyReported   the session already reported {@code ready}
     * @param idleDiscovering phase is DISCOVERING and no carrier attempt is in progress
     * @return true to keep (re)trying discovery
     */
    static boolean discoveryWanted(boolean sessionLive, boolean readyReported, boolean idleDiscovering) {
        return sessionLive && (!readyReported || idleDiscovering);
    }
}
