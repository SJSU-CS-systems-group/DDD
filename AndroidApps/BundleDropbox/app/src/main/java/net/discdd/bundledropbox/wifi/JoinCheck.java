package net.discdd.bundledropbox.wifi;

/**
 * Pure-Java decision of whether a connection report means the dropbox has joined the carrier it asked for.
 * Mirrors the stock client's checks ({@code DDDWifiDirect.java:94-115, 329-354}, Research/R2 §1.3) plus one
 * the client does not need: the dropbox must <em>not</em> be the group owner (it connects with GO intent 0
 * and runs no server for the carrier to reach).
 */
final class JoinCheck {
    /** Outcome for one {@code WifiP2pInfo} (+ optional {@code WifiP2pGroup}) report. */
    enum Verdict {
        /** No group yet, or the GO address is not known yet: keep waiting (bounded by CONNECT_MS). */
        WAIT,
        /** GO negotiation made us the owner: the carrier would lose its own clients; fail the attempt. */
        BECAME_GO,
        /** We are a client, but of a different device than the carrier we asked for; fail the attempt. */
        WRONG_GO,
        /** Joined the carrier as a client; the GO address can be handed to the VisitRunner. */
        JOINED
    }

    private JoinCheck() {
    }

    /**
     * @param groupFormed       {@code WifiP2pInfo.groupFormed}
     * @param weAreGroupOwner   {@code WifiP2pInfo.isGroupOwner}
     * @param goAddressKnown    {@code WifiP2pInfo.groupOwnerAddress != null}
     * @param ownerAddress      {@code WifiP2pGroup.getOwner().deviceAddress} if a group came with the report,
     *                          else null. The {@code requestConnectionInfo} polling fallback has no group, so
     *                          only "we are not GO" can be checked there.
     * @param expectedAddress   P2P device address of the carrier we connected to
     * @return the verdict
     */
    static Verdict evaluate(boolean groupFormed,
                            boolean weAreGroupOwner,
                            boolean goAddressKnown,
                            String ownerAddress,
                            String expectedAddress) {
        if (!groupFormed || !goAddressKnown) return Verdict.WAIT;
        if (weAreGroupOwner) return Verdict.BECAME_GO;
        // Same comparison as the client's sameAddressAs (MAC strings may differ in case).
        if (ownerAddress != null && !ownerAddress.equalsIgnoreCase(expectedAddress)) return Verdict.WRONG_GO;
        return Verdict.JOINED;
    }
}
