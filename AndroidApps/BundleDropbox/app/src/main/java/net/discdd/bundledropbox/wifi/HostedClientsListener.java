package net.discdd.bundledropbox.wifi;

/**
 * Notified when the number of P2P clients in the dropbox's hosted group changes, as reported by
 * {@code WifiP2pGroup.getClientList()} on {@code WIFI_P2P_CONNECTION_CHANGED_ACTION}.
 *
 * <p>Why the role cycle needs this (audit finding m5, {@code idle-switch-ignores-joined-clients}): the
 * {@code ActivityGate} only learns about a stock client at its first RPC. A client that has joined the group
 * but is still probing port 7777 or doing the TLS handshake holds no token, so an idle-switch at dwell
 * expiry would tear the group down under it; the client then stamps {@code lastExchange} and skips this
 * transportId until it rotates (Research/R3 §7). Joined-but-silent clients are visible only here.
 *
 * <p>Edge-triggered: called only when the count changes, including the drop to 0 when the group is removed,
 * lost, or a start/stop op supersedes hosting. Called on the main thread; implementations must be fast and
 * re-post to their own thread (the controller's {@code post}).
 */
@FunctionalInterface
public interface HostedClientsListener {
    /**
     * @param count P2P clients currently in our group (0 when not hosting)
     */
    void onHostedClientCountChanged(int count);
}
