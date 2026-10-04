package net.discdd.bundledropbox.cycle;

import org.junit.Test;

import static net.discdd.bundledropbox.cycle.CycleHarness.CAP;
import static net.discdd.bundledropbox.cycle.CycleHarness.CLIENT;
import static net.discdd.bundledropbox.cycle.CycleHarness.TRANSPORT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Audit m5: a stock client that joined the hosted group but has not sent its first RPC holds no gate token.
 * With {@link RadioDriver#hostedClientCount()} the HOST drain keeps admissions open for a short grace
 * (bounded by {@link RoleCycleController#JOINED_CLIENT_GRACE_MS} and the drain cap) instead of tearing
 * the group down under it.
 */
public class RoleCycleControllerJoinGraceTest {
    private static final long GRACE = RoleCycleController.JOINED_CLIENT_GRACE_MS;

    @Test
    public void silentJoinedClientIsAdmittedDuringGraceAndFinishes() {
        CycleHarness h = new CycleHarness();
        h.startToHosting();
        long expiry = h.c.deadlineAtMillis();
        h.radio.hostedClients = 1;

        h.s.advance(CLIENT);
        assertEquals("not torn down although no token is held", CycleMode.HOST_DRAINING, h.c.mode());
        assertEquals(expiry + CAP, h.c.deadlineAtMillis());
        assertTrue("admissions stay open for the joined client", h.gate.isOpen());
        assertEquals("not discoverable while draining", 1, h.radio.withdrawals);

        h.s.advance(1_500); // TLS handshake done, first RPC
        ActivityGate.Token session = h.gate.tryAdmit("192.168.49.10");
        assertNotNull(session);
        h.s.runReady();
        assertTrue("every joined client admitted: grace over, admissions closed", h.gate.isClosing());
        assertNull(h.gate.tryAdmit("192.168.49.11"));
        assertEquals(CycleMode.HOST_DRAINING, h.c.mode());
        assertEquals("cap unchanged by the grace", expiry + CAP, h.c.deadlineAtMillis());

        h.s.advance(4_000);
        session.close();
        h.s.runReady();
        assertEquals(CycleMode.HOST_STOPPING, h.c.mode());
        assertFalse(session.isCancelled());
    }

    @Test
    public void graceIsBoundedWhenTheClientStaysSilent() {
        CycleHarness h = new CycleHarness();
        h.startToHosting();
        h.radio.hostedClients = 1;
        h.s.advance(CLIENT);
        assertEquals(CycleMode.HOST_DRAINING, h.c.mode());
        h.s.advance(GRACE - 1);
        assertEquals(CycleMode.HOST_DRAINING, h.c.mode());
        assertTrue(h.gate.isOpen());
        h.s.advance(1);
        assertEquals(CycleMode.HOST_STOPPING, h.c.mode());
        assertTrue(h.gate.isClosing());
    }

    @Test
    public void graceEndsAsSoonAsTheJoinedClientLeaves() {
        CycleHarness h = new CycleHarness();
        h.startToHosting();
        h.radio.hostedClients = 1;
        h.s.advance(CLIENT);
        assertEquals(CycleMode.HOST_DRAINING, h.c.mode());
        h.s.advance(200);
        h.radio.hostedClients = 0;
        h.c.hostedClientsChanged();
        h.s.runReady();
        assertEquals(CycleMode.HOST_STOPPING, h.c.mode());
    }

    @Test
    public void pollingNoticesALeaveWithoutAHint() {
        CycleHarness h = new CycleHarness();
        h.startToHosting();
        h.radio.hostedClients = 1;
        h.s.advance(CLIENT);
        h.radio.hostedClients = 0;
        h.s.advance(RoleCycleController.JOINED_CLIENT_POLL_MS);
        assertEquals(CycleMode.HOST_STOPPING, h.c.mode());
    }

    @Test
    public void graceNeverOutlastsTheDrainCap() {
        CycleHarness h = new CycleHarness(CycleConfig.DEFAULTS.withDrainCapMs(2_000));
        h.startToHosting();
        h.radio.hostedClients = 1;
        h.s.advance(CLIENT);
        assertEquals(CycleMode.HOST_DRAINING, h.c.mode());
        h.s.advance(2_000);
        assertEquals(CycleMode.HOST_STOPPING, h.c.mode());
    }

    @Test
    public void zeroDrainCapMeansNoGrace() {
        CycleHarness h = new CycleHarness(CycleConfig.DEFAULTS.withDrainCapMs(0));
        h.startToHosting();
        h.radio.hostedClients = 3;
        h.s.advance(CLIENT);
        assertEquals(CycleMode.HOST_STOPPING, h.c.mode());
    }

    @Test
    public void busyHostAlsoWaitsForAnExtraJoinedClient() {
        CycleHarness h = new CycleHarness();
        h.startToHosting();
        ActivityGate.Token first = h.gate.tryAdmit("192.168.49.10");
        h.radio.hostedClients = 2;
        h.s.advance(CLIENT);
        assertEquals(CycleMode.HOST_DRAINING, h.c.mode());
        assertTrue("second joined client can still get in", h.gate.isOpen());

        first.close(); // the first client finishing does not end the drain while the grace lasts
        h.radio.hostedClients = 1; // ... and it leaves the group afterwards
        h.s.runReady();
        assertEquals(CycleMode.HOST_DRAINING, h.c.mode());

        ActivityGate.Token second = h.gate.tryAdmit("192.168.49.11");
        h.s.runReady();
        assertTrue(h.gate.isClosing());
        second.close();
        h.s.runReady();
        assertEquals(CycleMode.HOST_STOPPING, h.c.mode());
    }

    @Test
    public void visitSideIgnoresHostedClientCount() {
        CycleHarness h = new CycleHarness();
        h.blob.set(false);
        h.radio.hostedClients = 5;
        h.c.start();
        h.s.runReady();
        h.ready();
        assertEquals(CycleMode.VISITING, h.c.mode());
        h.s.advance(TRANSPORT);
        assertEquals(CycleMode.VISIT_STOPPING, h.c.mode());
    }

    @Test
    public void throwingDriverCountMeansNoGrace() {
        CycleHarness h = new CycleHarness();
        h.startToHosting();
        h.radio.hostedClientsError = new IllegalStateException("boom");
        h.s.advance(CLIENT);
        assertEquals(CycleMode.HOST_STOPPING, h.c.mode());
    }

    @Test
    public void pauseDuringGraceStopsAtOnce() {
        CycleHarness h = new CycleHarness();
        h.startToHosting();
        h.radio.hostedClients = 1;
        h.s.advance(CLIENT);
        assertEquals(CycleMode.HOST_DRAINING, h.c.mode());
        h.c.pause();
        h.s.runReady();
        assertEquals(CycleMode.HOST_STOPPING, h.c.mode());
        assertTrue(h.gate.isClosing());
        h.s.advance(GRACE); // no stale grace poll may act after the stop
        assertEquals(CycleMode.HOST_STOPPING, h.c.mode());
        h.ready();
        assertEquals(CycleMode.STOPPED, h.c.mode());
    }
}
