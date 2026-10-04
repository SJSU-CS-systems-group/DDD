package net.discdd.bundledropbox.wifi;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JVM tests for {@link AvailabilityEdges}: only real environment changes are edges, so a role-loss signal
 * (which the driver no longer feeds in) cannot hide the next Wi-Fi-disabled or channel-recovered edge
 * (audit findings m3/m10).
 */
public class AvailabilityEdgesTest {
    @Test
    public void firstUsableObservationIsAnEdgeButInitialUnusableIsNot() {
        AvailabilityEdges e = new AvailabilityEdges();
        assertFalse(e.observe(false)); // e.g. sticky "disabled" broadcast at registration
        assertTrue(e.observe(true));   // P2P enabled -> retryNow
        assertTrue(e.lastAvailable());
    }

    @Test
    public void stickyRepeatsAreNotEdges() {
        AvailabilityEdges e = new AvailabilityEdges();
        assertTrue(e.observe(true));
        assertFalse(e.observe(true)); // receiver re-registered after a permission grant
        assertFalse(e.observe(true));
    }

    @Test
    public void disableAfterRoleLossIsStillReported() {
        // HOSTING group lost while P2P stays on: the driver reports onRoleLost and does NOT observe here.
        // Before the fix, that stored "unavailable", so the later real disable produced no edge.
        AvailabilityEdges e = new AvailabilityEdges();
        assertTrue(e.observe(true));
        // ... hosted group lost (role loss, not observed) ...
        assertTrue("Wi-Fi disabled during the next VISIT must reach roleLost", e.observe(false));
        assertTrue("Wi-Fi re-enabled must reach retryNow", e.observe(true));
    }

    @Test
    public void channelLossAndRecoveryAreAPairOfEdges() {
        // The channel is part of availability: loss -> false edge (roleLost), re-created channel's
        // P2P state answer -> true edge (retryNow leaves UNAVAILABLE without the 30 s timer).
        AvailabilityEdges e = new AvailabilityEdges();
        assertTrue(e.observe(true));
        assertTrue(e.observe(false));
        assertFalse(e.lastAvailable());
        assertFalse(e.observe(false)); // a broadcast while the channel is still down
        assertTrue(e.observe(true));
    }
}
