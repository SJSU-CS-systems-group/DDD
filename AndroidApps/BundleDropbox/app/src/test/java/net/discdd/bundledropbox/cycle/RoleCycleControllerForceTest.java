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
    private static final long CAP = CycleConfig.DEFAULT_DRAIN_CAP_MS;

    private FakeScheduler s;
    private FakeRadio radio;
    private AtomicBoolean blob;
    private ActivityGate gate;
    private RoleCycleController c;

    @Before
    public void setUp() {
        s = new FakeScheduler();
        radio = new FakeRadio();
        blob = new AtomicBoolean(true);
        gate = new ActivityGate();
        c = new RoleCycleController(radio, s, s, gate, blob::get, CycleConfig.DEFAULTS);
    }

    private void ready() {
        radio.last().ready();
        s.runReady();
    }

    private void startToHosting() {
        c.start();
        s.runReady();
        ready();
        assertEquals(CycleMode.HOSTING, c.mode());
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
        blob.set(true); // e.g. a carrier delivered a blob during the previous visit
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
        for (int i = 0; i < RoleCycleController.MAX_ATTEMPTS; i++) {
            radio.last().fail("p2p busy");
            s.runReady();
            if (i < RoleCycleController.MAX_ATTEMPTS - 1) s.advance(RoleCycleController.RETRY_BACKOFF_BASE_MS << i);
        }
        assertEquals(CycleMode.UNAVAILABLE, c.mode());
        blob.set(true);
        c.forceHost();
        s.runReady();
        assertEquals(CycleMode.HOST_STARTING, c.mode());
        assertEquals("startHosting", radio.last().op);
    }
}
