package net.discdd.bundledropbox.wifi;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JVM tests for {@link AvailabilityEdges}: only real environment changes are edges, so a role-loss signal
 * (which the driver no longer feeds in) cannot hide the next Wi-Fi-disabled or channel-recovered edge
 * (audit findings m3/m10), plus the driver's edge-or-role-lost decision {@link AvailabilityEdges#report}
 * (audit CL-7).
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

    @Test
    public void reportPrefersTheEdgeOverRoleLoss() {
        // P2P disabled during an established role: the "unavailable" edge is reported (the service maps it
        // to roleLost), not a second, separate role loss.
        AvailabilityEdges e = new AvailabilityEdges();
        assertEquals(AvailabilityEdges.Report.EDGE, e.report(true, false));
        assertEquals(AvailabilityEdges.Report.EDGE, e.report(false, true));
    }

    @Test
    public void roleLostWithoutAnEdgeIsStillReported() {
        // m3 symptom guard: P2P disabled (edge), quickly on and off again, and the next "disabled" observation
        // is not an edge while a VISIT runs. The role loss must still reach the cycle.
        AvailabilityEdges e = new AvailabilityEdges();
        assertEquals(AvailabilityEdges.Report.EDGE, e.report(true, false));
        assertEquals(AvailabilityEdges.Report.EDGE, e.report(false, false));
        assertEquals(AvailabilityEdges.Report.ROLE_LOST, e.report(false, true));
    }

    @Test
    public void noEdgeAndNoRoleLossReportsNothing() {
        AvailabilityEdges e = new AvailabilityEdges();
        assertEquals(AvailabilityEdges.Report.NONE, e.report(false, false)); // sticky "disabled", nothing running
        assertEquals(AvailabilityEdges.Report.EDGE, e.report(true, false));
        assertEquals(AvailabilityEdges.Report.NONE, e.report(true, false));  // receiver re-registered
        assertTrue(e.lastAvailable());
    }
}
