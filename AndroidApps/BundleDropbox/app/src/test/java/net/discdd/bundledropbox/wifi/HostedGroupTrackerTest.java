package net.discdd.bundledropbox.wifi;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.discdd.bundledropbox.wifi.HostedGroupTracker.Event.LOST;
import static net.discdd.bundledropbox.wifi.HostedGroupTracker.Event.NOT_FORMED;
import static net.discdd.bundledropbox.wifi.HostedGroupTracker.Event.UPDATED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JVM tests for {@link HostedGroupTracker}: loss is recognized only after the group was observed as formed
 * (audit finding m2), and the client diff/count that feeds the role cycle's joined-client check (m5) is
 * keyed by device address.
 */
public class HostedGroupTrackerTest {
    private static Map<String, String> clients(String... addressLabelPairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < addressLabelPairs.length; i += 2) m.put(addressLabelPairs[i], addressLabelPairs[i + 1]);
        return m;
    }

    @Test
    public void broadcastsBeforeFormationAreIgnored() {
        HostedGroupTracker t = new HostedGroupTracker();
        // startHosting step 1 removing a stale carrier group, then createGroup in progress.
        assertEquals(NOT_FORMED, t.onConnectionChanged(false, Map.of()));
        assertEquals(NOT_FORMED, t.onConnectionChanged(true, clients("a", "A")));
        assertFalse(t.isFormed());
        assertEquals(0, t.clientCount());
        assertFalse(t.lastChange().countChanged);
    }

    @Test
    public void groupGoneAfterFormationIsLostEvenWhileStarting() {
        HostedGroupTracker t = new HostedGroupTracker();
        t.formed(Map.of());
        assertEquals(LOST, t.onConnectionChanged(false, Map.of()));
        assertFalse(t.isFormed());
        // Once lost, further group-less broadcasts are not reported again.
        assertEquals(NOT_FORMED, t.onConnectionChanged(false, Map.of()));
    }

    @Test
    public void clientsJoinAndLeaveWithCountEdges() {
        HostedGroupTracker t = new HostedGroupTracker();
        HostedGroupTracker.ClientChange c = t.formed(Map.of());
        assertFalse(c.countChanged);

        assertEquals(UPDATED, t.onConnectionChanged(true, clients("a", "Alice")));
        c = t.lastChange();
        assertEquals(List.of("Alice"), c.joined);
        assertTrue(c.countChanged);
        assertEquals(1, c.count);

        assertEquals(UPDATED, t.onConnectionChanged(true, clients("a", "Alice", "b", "Bob")));
        assertEquals(List.of("Bob"), t.lastChange().joined);
        assertEquals(2, t.clientCount());

        // A broadcast with the same clients (e.g. a GO address update) is not a count edge.
        assertEquals(UPDATED, t.onConnectionChanged(true, clients("b", "Bob", "a", "Alice")));
        assertFalse(t.lastChange().countChanged);
        assertTrue(t.lastChange().joined.isEmpty());

        assertEquals(UPDATED, t.onConnectionChanged(true, clients("b", "Bob")));
        assertEquals(List.of("Alice"), t.lastChange().left);
        assertEquals(1, t.lastChange().count);
    }

    @Test
    public void sameNameDifferentDevicesCountTwice() {
        HostedGroupTracker t = new HostedGroupTracker();
        t.formed(clients("a", "Pixel", "b", "Pixel"));
        assertEquals(2, t.clientCount());
        assertEquals(List.of("Pixel", "Pixel"), t.lastChange().joined);
    }

    @Test
    public void reusedGroupReportsClientsAlreadyPresent() {
        // A persistent group reused at startHosting may already have clients that re-joined.
        HostedGroupTracker t = new HostedGroupTracker();
        HostedGroupTracker.ClientChange c = t.formed(clients("a", "A"));
        assertTrue(c.countChanged);
        assertEquals(1, c.count);
    }

    @Test
    public void lossAndResetDropTheCountToZeroOnce() {
        HostedGroupTracker t = new HostedGroupTracker();
        t.formed(clients("a", "A", "b", "B"));
        assertEquals(LOST, t.onConnectionChanged(false, Map.of()));
        HostedGroupTracker.ClientChange c = t.lastChange();
        assertTrue(c.countChanged);
        assertEquals(0, c.count);
        assertEquals(List.of("A", "B"), c.left);
        assertFalse("already 0: no second edge", t.reset().countChanged);
    }

    @Test
    public void recheckAfterStaleLossRestoresTracking() {
        HostedGroupTracker t = new HostedGroupTracker();
        t.formed(clients("a", "A"));
        t.onConnectionChanged(false, Map.of()); // stale broadcast while starting
        HostedGroupTracker.ClientChange c = t.formed(clients("a", "A")); // requestGroupInfo: still ours
        assertTrue(t.isFormed());
        assertTrue(c.countChanged);
        assertEquals(1, c.count);
        assertEquals(UPDATED, t.onConnectionChanged(true, clients("a", "A")));
    }
}
