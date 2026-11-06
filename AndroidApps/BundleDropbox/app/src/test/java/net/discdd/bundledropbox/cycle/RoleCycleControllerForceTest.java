package net.discdd.bundledropbox.cycle;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Fake-clock tests for the operator "Visit transport now" / "Host now" controls
 * ({@link RoleCycleController#forceVisit()} / {@link RoleCycleController#forceHost()}), added at integration
 * for the Status tab. A forced switch must reuse the ordinary drain and stop paths (in-flight work still
 * finishes, bounded by the drain cap) and must respect the bootstrap rule (no HOST without a recency blob).
 */
public class RoleCycleControllerForceTest {
    private static final long CAP = CycleHarness.CAP;

    private CycleHarness h;
    // Short aliases for the harness parts.
    private FakeScheduler s;
    private FakeRadio radio;
    private AtomicBoolean blob;
    private ActivityGate gate;
    private RoleCycleController c;

    @Before
    public void setUp() {
        h = new CycleHarness();
        s = h.s;
        radio = h.radio;
        blob = h.blob;
        gate = h.gate;
        c = h.c;
    }

    private void ready() {
        h.ready();
    }

    private void startToHosting() {
        h.startToHosting();
    }

    @Test
    public void forceVisitWhileHostingIdleSwitchesNow() {
        startToHosting();
        s.advance(5_000);
        c.forceVisit();
        s.runReady();
        assertEquals(CycleMode.HOST_STOPPING, c.mode());
        ready();
        assertEquals(CycleMode.VISIT_STARTING, c.mode());
        assertEquals(List.of("startHosting", "stopHosting", "startVisit"), radio.ops());
    }

    @Test
    public void forceVisitWhileHostingDrainsActiveClient() {
        startToHosting();
        ActivityGate.Token session = gate.tryAdmit("192.168.49.10");
        assertNotNull(session);
        c.forceVisit();
        s.runReady();
        assertEquals("in-flight client finishes first", CycleMode.HOST_DRAINING, c.mode());
        assertEquals("cap measured from the forced expiry", s.nowMillis() + CAP, c.deadlineAtMillis());
        session.close();
        s.runReady();
        assertEquals(CycleMode.HOST_STOPPING, c.mode());
        ready();
        assertEquals(CycleMode.VISIT_STARTING, c.mode());
    }

    @Test
    public void forceHostIgnoredWithoutRecencyBlob() {
        blob.set(false);
        c.start();
        s.runReady();
        ready();
        assertEquals(CycleMode.VISITING, c.mode());
        c.forceHost();
        s.runReady();
        assertEquals("bootstrap: clients would abort without a blob", CycleMode.VISITING, c.mode());
        assertEquals(List.of("startVisit"), radio.ops());
    }

    @Test
    public void forceHostWhileVisitStartingSupersedesTheStart() {
        blob.set(false);
        c.start();
        s.runReady();
        assertEquals(CycleMode.VISIT_STARTING, c.mode());
        FakeRadio.Call startVisit = radio.last();
        blob.set(true); // e.g. a server grant arrived during the previous visit
        c.forceHost();
        s.runReady();
        assertEquals(CycleMode.VISIT_STOPPING, c.mode());
        FakeRadio.Call stopVisit = radio.last();
        startVisit.ready(); // late ready of the superseded start: stale by generation
        s.runReady();
        assertEquals(CycleMode.VISIT_STOPPING, c.mode());
        stopVisit.ready();
        s.runReady();
        assertEquals(CycleMode.HOST_STARTING, c.mode());
    }

    @Test
    public void forceHostWhileHostStoppingOverridesNextRole() {
        startToHosting();
        s.advance(CycleConfig.DEFAULT_CLIENT_PERIOD_MS);
        assertEquals(CycleMode.HOST_STOPPING, c.mode());
        c.forceHost();
        s.runReady();
        ready();
        assertEquals("operator choice wins over the normal HOST->VISIT alternation", CycleMode.HOST_STARTING,
                     c.mode());
        ready();
        assertEquals(CycleMode.HOSTING, c.mode());
        s.advance(CycleConfig.DEFAULT_CLIENT_PERIOD_MS);
        ready();
        assertEquals("the override is used once", CycleMode.VISIT_STARTING, c.mode());
    }

    @Test
    public void forceIgnoredWhilePaused() {
        startToHosting();
        c.pause();
        s.runReady();
        ready();
        assertEquals(CycleMode.STOPPED, c.mode());
        c.forceVisit();
        s.runReady();
        assertEquals(CycleMode.STOPPED, c.mode());
        assertEquals(List.of("startHosting", "stopHosting"), radio.ops());
    }

    @Test
    public void forceHostFromUnavailableReplacesFailedStart() {
        blob.set(false);
        c.start();
        s.runReady();
        h.failAllAttempts();
        assertEquals(CycleMode.UNAVAILABLE, c.mode());
        blob.set(true);
        c.forceHost();
        s.runReady();
        assertEquals(CycleMode.HOST_STARTING, c.mode());
        assertEquals("startHosting", radio.last().op);
    }
}
