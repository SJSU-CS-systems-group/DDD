package net.discdd.bundledropbox.wifi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java bookkeeping for the group {@link DropboxWifiHost} owns: whether it has been observed as formed,
 * which P2P clients are in it, and what a {@code WIFI_P2P_CONNECTION_CHANGED_ACTION} means for it.
 *
 * <p>Two decisions live here so they are JVM-testable:
 * <ul>
 *   <li><b>Loss classification.</b> A broadcast without a GO group means "our group is gone" only once the
 *       group was observed as formed ({@link #formed}). Before that, a group-less broadcast is expected noise
 *       (startHosting step 1 removing a stale carrier group, or {@code createGroup} still in progress) and is
 *       {@link Event#NOT_FORMED}. Before audit finding m2 the host acted on loss only in HOSTING, so a group
 *       lost between "formed" and "service added" went unreported.</li>
 *   <li><b>Client diff and count edges</b> for the UI log ("new clients" / "lost clients", copied from
 *       {@code DDDWifiServer}'s receiver) and for {@link HostedClientsListener}. Clients are keyed by P2P
 *       device address, not by the display label, so two phones with the same name still count twice.</li>
 * </ul>
 *
 * <p>Main-thread use only (not synchronized).
 */
final class HostedGroupTracker {
    /** Meaning of one connection-changed broadcast for the hosted group. */
    enum Event {
        /** Our group has not been observed as formed yet: nothing to track or lose. */
        NOT_FORMED,
        /** Our group is still there; client list (maybe) updated. */
        UPDATED,
        /** Our group was formed and the broadcast no longer shows us as its GO. */
        LOST
    }

    /** Joined/left clients (display labels) and the count after one update. */
    static final class ClientChange {
        static final ClientChange NONE = new ClientChange(List.of(), List.of(), false, 0);

        final List<String> joined;
        final List<String> left;
        /** True if {@link #count} differs from the count before this update. */
        final boolean countChanged;
        final int count;

        ClientChange(List<String> joined, List<String> left, boolean countChanged, int count) {
            this.joined = List.copyOf(joined);
            this.left = List.copyOf(left);
            this.countChanged = countChanged;
            this.count = count;
        }
    }

    private boolean formed;
    /** Device address to display label, in join order. */
    private final Map<String, String> clients = new LinkedHashMap<>();
    private ClientChange lastChange = ClientChange.NONE;

    /** @return true between {@link #formed} and {@link #reset}/{@link Event#LOST} */
    boolean isFormed() {
        return formed;
    }

    /** @return number of clients currently in the group (0 when not formed) */
    int clientCount() {
        return clients.size();
    }

    /** @return the change computed by the last {@link #formed}, {@link #onConnectionChanged} or {@link #reset} */
    ClientChange lastChange() {
        return lastChange;
    }

    /**
     * Our group was observed as GO (startHosting reached "group formed", or a re-check confirmed it).
     *
     * @param clientsByAddress current client list, device address to display label
     * @return the client change against what was tracked before
     */
    ClientChange formed(Map<String, String> clientsByAddress) {
        formed = true;
        return replaceClients(clientsByAddress);
    }

    /**
     * Classifies a {@code WIFI_P2P_CONNECTION_CHANGED_ACTION} while the host is starting or hosting.
     *
     * @param weAreGoOfAGroup  the broadcast's group is non-null and we are its owner
     * @param clientsByAddress that group's client list (ignored unless {@code weAreGoOfAGroup})
     * @return what the broadcast means; on {@link Event#LOST} the clients are cleared ({@link #lastChange}
     *         carries the drop to 0) and the tracker is no longer formed
     */
    Event onConnectionChanged(boolean weAreGoOfAGroup, Map<String, String> clientsByAddress) {
        if (!formed) {
            lastChange = ClientChange.NONE;
            return Event.NOT_FORMED;
        }
        if (!weAreGoOfAGroup) {
            reset();
            return Event.LOST;
        }
        replaceClients(clientsByAddress);
        return Event.UPDATED;
    }

    /**
     * Forgets the group (stopped, superseded, failed, or Wi-Fi Direct lost).
     *
     * @return the change; {@code countChanged} is true if clients were being tracked
     */
    ClientChange reset() {
        formed = false;
        return replaceClients(Map.of());
    }

    private ClientChange replaceClients(Map<String, String> now) {
        int before = clients.size();
        List<String> joined = new ArrayList<>();
        List<String> left = new ArrayList<>();
        now.forEach((address, label) -> {
            if (!clients.containsKey(address)) joined.add(label);
        });
        clients.forEach((address, label) -> {
            if (!now.containsKey(address)) left.add(label);
        });
        clients.keySet().retainAll(now.keySet());
        now.forEach(clients::putIfAbsent);
        lastChange = new ClientChange(joined, left, clients.size() != before, clients.size());
        return lastChange;
    }
}
