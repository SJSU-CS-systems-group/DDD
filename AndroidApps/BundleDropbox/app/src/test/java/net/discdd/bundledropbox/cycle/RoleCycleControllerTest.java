package net.discdd.bundledropbox.cycle;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Fake-clock tests for the role cycle semantics in 03 Architecture ("Role cycle", "Admission and drain").
 * Everything runs on the test thread: {@link FakeScheduler} is both the clock and the owner thread,
 * {@link FakeRadio} records radio calls that the test completes by hand.
 */
public class RoleCycleControllerTest {
    private static final long CLIENT = CycleConfig.DEFAULT_CLIENT_PERIOD_MS;
    private static final long TRANSPORT = CycleConfig.DEFAULT_TRANSPORT_PERIOD_MS;
    private static final long CAP = CycleConfig.DEFAULT_DRAIN_CAP_MS;

    private FakeScheduler s;
    private FakeRadio radio;
    private AtomicBoolean blob;
    private ActivityGate gate;
    private RoleCycleController c;
    private final List<CycleMode> modes = new ArrayList<>();
    private final List<Long> deadlines = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    @Before
    public void setUp() {
        s = new FakeScheduler();
        radio = new FakeRadio();
        blob = new AtomicBoolean(true);
        gate = new ActivityGate();
        newController(CycleConfig.DEFAULTS);
    }

    private void newController(CycleConfig config) {
        c = new RoleCycleController(radio, s, s, gate, blob::get, config);
        c.addListener(new CycleListener() {
            @Override
            public void onModeChanged(CycleMode previous, CycleMode current, long startedAt, long deadlineAt) {
                modes.add(current);
                deadlines.add(deadlineAt);
            }

            @Override
            public void onCycleError(CycleMode mode, String reason) {
                errors.add(mode + ":" + reason);
            }
        });
    }

    /** Complete the most recent radio call successfully and let the controller react. */
    private void ready() {
        radio.last().ready();
        s.runReady();
    }

    private void startToHosting() {
        c.start();
        s.runReady();
        assertEquals(CycleMode.HOST_STARTING, c.mode());
        assertEquals("startHosting", radio.last().op);
        ready();
        assertEquals(CycleMode.HOSTING, c.mode());
    }

    private void hostingToVisiting() {
        s.advance(CLIENT);
        assertEquals(CycleMode.HOST_STOPPING, c.mode());
        ready();
        assertEquals(CycleMode.VISIT_STARTING, c.mode());
        assertEquals("startVisit", radio.last().op);
        ready();
        assertEquals(CycleMode.VISITING, c.mode());
    }

    @Test
    public void idleSwitchAndTimersStartOnlyWhenReady() {
        c.start();
        s.runReady();
        assertEquals(CycleMode.HOST_STARTING, c.mode());
        assertEquals(CycleListener.NO_DEADLINE, c.deadlineAtMillis());

        s.advance(10_000); // slow group formation must not consume dwell time
        ready();
        long readyAt = s.nowMillis();
        assertEquals(CycleMode.HOSTING, c.mode());
        assertEquals(readyAt + CLIENT, c.deadlineAtMillis());

        s.advance(CLIENT - 1);
        assertEquals(CycleMode.HOSTING, c.mode());
        s.advance(1);
        // Idle at expiry: no drain state, straight to teardown.
        assertEquals(CycleMode.HOST_STOPPING, c.mode());
        assertFalse(modes.contains(CycleMode.HOST_DRAINING));
        assertTrue(gate.isClosing());

        ready();
        assertEquals(CycleMode.VISIT_STARTING, c.mode());
        assertTrue("admissions open while the next role prepares", gate.isOpen());
        s.advance(5_000); // slow discovery start
        ready();
        assertEquals(CycleMode.VISITING, c.mode());
        assertEquals(s.nowMillis() + TRANSPORT, c.deadlineAtMillis());

        s.advance(TRANSPORT);
        assertEquals(CycleMode.VISIT_STOPPING, c.mode());
        ready();
        assertEquals("blob present: back to HOST", CycleMode.HOST_STARTING, c.mode());
        assertEquals(List.of("startHosting", "stopHosting", "startVisit", "stopVisit", "startHosting"), radio.ops());
    }

    @Test
    public void drainFinishesBeforeCap() {
        startToHosting();
        ActivityGate.Token session = gate.tryAdmit("192.168.49.10");
        assertNotNull(session);
        long expiry = c.deadlineAtMillis();

        s.advance(CLIENT);
        assertEquals(CycleMode.HOST_DRAINING, c.mode());
        assertEquals(expiry + CAP, c.deadlineAtMillis());
        assertNull("new client rejected while draining", gate.tryAdmit("192.168.49.11"));
        ActivityGate.Token followOn = gate.tryAdmit("192.168.49.10");
        assertNotNull("follow-on RPC from an admitted client passes", followOn);

        s.advance(4_000);
        followOn.close();
        s.runReady();
        assertEquals(CycleMode.HOST_DRAINING, c.mode());
        session.close();
        s.runReady();
        assertEquals(CycleMode.HOST_STOPPING, c.mode());
        assertEquals(expiry + 4_000, c.modeStartedAtMillis());

        s.advance(CAP * 2); // the cancelled cap timer must not fire
        assertEquals(CycleMode.HOST_STOPPING, c.mode());
        assertEquals(1, radio.ops().stream().filter("stopHosting"::equals).count());
        assertFalse(session.isCancelled());
    }

    @Test
    public void stalledWorkHitsCapAndIsCancelled() {
        startToHosting();
        ActivityGate.Token t = gate.tryAdmit("192.168.49.10");
        AtomicBoolean aborted = new AtomicBoolean();
        t.setCancelHook(() -> aborted.set(true));
        long expiry = c.deadlineAtMillis();

        s.advance(CLIENT);
        assertEquals(CycleMode.HOST_DRAINING, c.mode());
        s.advance(CAP - 1);
        assertEquals(CycleMode.HOST_DRAINING, c.mode());
        assertFalse(aborted.get());
        s.advance(1);
        assertEquals(CycleMode.HOST_STOPPING, c.mode());
        assertEquals(expiry + CAP, c.modeStartedAtMillis());
        assertTrue(aborted.get());
        assertTrue(t.isCancelled());
        assertEquals(0, gate.activeCount());
        t.close(); // late close from the aborted worker is harmless
        s.runReady();
        assertEquals(CycleMode.HOST_STOPPING, c.mode());
    }

    @Test
    public void drainDeadlineNeverExtendedByNewArrivals() {
        startToHosting();
        gate.tryAdmit("10.0.0.1");
        s.advance(CLIENT);
        long cap = c.deadlineAtMillis();
        for (int i = 0; i < CAP / 1_000 - 1; i++) {
            s.advance(1_000);
            assertNotNull(gate.tryAdmit("10.0.0.1")); // admitted session keeps sending RPCs
            assertEquals(cap, c.deadlineAtMillis());
            assertEquals(CycleMode.HOST_DRAINING, c.mode());
        }
        s.advance(cap - s.nowMillis());
        assertEquals(CycleMode.HOST_STOPPING, c.mode());
        assertEquals(cap, c.modeStartedAtMillis());
    }

    @Test
    public void zeroDrainCapForcesImmediately() {
        newController(CycleConfig.DEFAULTS.withDrainCapMs(0));
        startToHosting();
        ActivityGate.Token t = gate.tryAdmit("10.0.0.1");
        s.advance(CLIENT);
        assertEquals(CycleMode.HOST_STOPPING, c.mode());
        assertTrue(t.isCancelled());
    }

    @Test
    public void staleCallbacksAreIgnored() {
        c.start();
        s.runReady();
        FakeRadio.Call first = radio.last();
        first.fail("busy");
        s.runReady();
        s.advance(RoleCycleController.RETRY_BACKOFF_BASE_MS);
        FakeRadio.Call second = radio.last();
        assertEquals("startHosting", second.op);
        assertTrue(second.gen > first.gen);

        first.ready(); // late success from the abandoned attempt
        s.runReady();
        assertEquals(CycleMode.HOST_STARTING, c.mode());
        first.fail("late"); // and a late failure
        s.runReady();
        assertEquals(1, errors.size());

        second.ready();
        s.runReady();
        assertEquals(CycleMode.HOSTING, c.mode());

        // A visitFinished from a visit that has already been stopped must not affect the next role.
        hostingToVisiting();
        FakeRadio.Call visit = radio.last();
        s.advance(TRANSPORT);
        ready(); // stopVisit done -> HOST_STARTING
        assertEquals(CycleMode.HOST_STARTING, c.mode());
        int calls = radio.calls.size();
        visit.visitFinished(true);
        visit.ready();
        s.runReady();
        assertEquals(CycleMode.HOST_STARTING, c.mode());
        assertEquals(calls, radio.calls.size());
    }

    @Test
    public void earlyReturnAfterSuccessfulVisit() {
        blob.set(false);
        c.start();
        s.runReady();
        ready();
        assertEquals(CycleMode.VISITING, c.mode());
        FakeRadio.Call visit = radio.last();

        visit.visitFinished(false); // failed exchange: keep searching
        s.runReady();
        assertEquals(CycleMode.VISITING, c.mode());

        ActivityGate.Token worker = gate.tryAdmit("visit");
        s.advance(5_000);
        visit.visitFinished(true);
        s.runReady();
        // Worker still holds its token: drain with the cap measured from the early end.
        assertEquals(CycleMode.VISIT_DRAINING, c.mode());
        assertEquals(s.nowMillis() + CAP, c.deadlineAtMillis());
        worker.close();
        s.runReady();
        assertEquals(CycleMode.VISIT_STOPPING, c.mode());
    }

    @Test
    public void earlyReturnDisabledKeepsVisiting() {
        newController(CycleConfig.DEFAULTS.withReturnAfterVisit(false));
        blob.set(false);
        c.start();
        s.runReady();
        ready();
        radio.last().visitFinished(true);
        s.runReady();
        assertEquals(CycleMode.VISITING, c.mode());
        s.advance(TRANSPORT);
        assertEquals(CycleMode.VISIT_STOPPING, c.mode());
    }

    @Test
    public void bootstrapLoopsVisitUntilBlobArrives() {
        blob.set(false);
        c.start();
        s.runReady();
        assertEquals(CycleMode.VISIT_STARTING, c.mode());
        for (int i = 0; i < 3; i++) {
            ready();
            assertEquals(CycleMode.VISITING, c.mode());
            s.advance(TRANSPORT);
            assertEquals(CycleMode.VISIT_STOPPING, c.mode());
            ready();
            assertEquals("no blob: HOST skipped", CycleMode.VISIT_STARTING, c.mode());
        }
        assertFalse(radio.ops().contains("startHosting"));

        ready();
        radio.last().visitFinished(true); // the carrier gave us a blob
        blob.set(true);
        s.runReady();
        assertEquals(CycleMode.VISIT_STOPPING, c.mode());
        ready();
        assertEquals(CycleMode.HOST_STARTING, c.mode());
    }

    @Test
    public void repeatedFailuresGoUnavailableThenRetry() {
        blob.set(false); // bootstrap: VISIT only, so there is no other role to fall back to
        c.start();
        s.runReady();
        radio.last().fail("p2p busy");
        s.runReady();
        assertEquals(CycleMode.VISIT_STARTING, c.mode());
        s.advance(RoleCycleController.RETRY_BACKOFF_BASE_MS - 1);
        assertEquals(1, radio.calls.size());
        s.advance(1);
        assertEquals(2, radio.calls.size());
        radio.last().fail("p2p busy");
        s.runReady();
        s.advance(RoleCycleController.RETRY_BACKOFF_BASE_MS * 2);
        assertEquals(3, radio.calls.size());
        radio.last().fail("p2p busy");
        s.runReady();

        assertEquals(CycleMode.UNAVAILABLE, c.mode());
        assertEquals(RoleCycleController.MAX_ATTEMPTS, errors.size());
        assertEquals(s.nowMillis() + RoleCycleController.UNAVAILABLE_RETRY_MS, c.deadlineAtMillis());
        assertTrue(gate.isClosing());

        s.advance(RoleCycleController.UNAVAILABLE_RETRY_MS - 1);
        assertEquals(3, radio.calls.size());
        s.advance(1);
        assertEquals(CycleMode.VISIT_STARTING, c.mode());
        assertEquals(4, radio.calls.size());
        ready();
        assertEquals(CycleMode.VISITING, c.mode());
    }

    @Test
    public void exhaustedStartFallsBackToOtherRoleOnce() {
        // E.g. VISIT keeps failing for a VISIT-only reason: with a blob the dropbox should still host.
        c.start();
        s.runReady();
        assertEquals("startHosting", radio.last().op);
        failAllAttempts();
        assertEquals("HOST exhausted: VISIT is tried next", CycleMode.VISIT_STARTING, c.mode());
        assertEquals("startVisit", radio.last().op);
        failAllAttempts();
        assertEquals("both roles failed: park", CycleMode.UNAVAILABLE, c.mode());
        assertEquals(2 * RoleCycleController.MAX_ATTEMPTS, radio.calls.size());

        s.advance(RoleCycleController.UNAVAILABLE_RETRY_MS);
        assertEquals("retry the last failed op first", "startVisit", radio.last().op);
        failAllAttempts();
        assertEquals("then the other role again", "startHosting", radio.last().op);
        ready();
        assertEquals(CycleMode.HOSTING, c.mode());
    }

    @Test
    public void noFallbackToHostWithoutBlob() {
        blob.set(false);
        c.start();
        s.runReady();
        failAllAttempts();
        assertEquals(CycleMode.UNAVAILABLE, c.mode());
        assertEquals(List.of("startVisit", "startVisit", "startVisit"), radio.ops());
    }

    @Test
    public void supersedingStopGetsItsOwnAttemptBudget() {
        c.start();
        s.runReady();
        radio.last().fail("busy");
        s.runReady();
        s.advance(RoleCycleController.RETRY_BACKOFF_BASE_MS);
        radio.last().fail("busy"); // START_HOST: 2 of 3 attempts used, backoff pending
        s.runReady();
        c.forceVisit();            // stop the failing start
        s.runReady();
        assertEquals("stopHosting", radio.last().op);
        radio.last().fail("busy"); // first failure of the STOP must be retried, not park the cycle
        s.runReady();
        assertEquals(CycleMode.HOST_STOPPING, c.mode());
        s.advance(RoleCycleController.RETRY_BACKOFF_BASE_MS);
        assertEquals("stopHosting", radio.last().op);
        ready();
        assertEquals(CycleMode.VISIT_STARTING, c.mode());
    }

    /** Fail the pending op and each of its backoff retries. */
    private void failAllAttempts() {
        for (int i = 0; i < RoleCycleController.MAX_ATTEMPTS; i++) {
            radio.last().fail("busy");
            s.runReady();
            if (i < RoleCycleController.MAX_ATTEMPTS - 1) s.advance(RoleCycleController.RETRY_BACKOFF_BASE_MS << i);
        }
    }

    @Test
    public void hostDrainWithdrawsAdvertisementButIdleSwitchDoesNot() {
        startToHosting();
        s.advance(CLIENT);
        assertEquals("idle expiry stops hosting directly (stopHosting clears services)", 0, radio.withdrawals);
        ready();
        ready();
        s.advance(TRANSPORT);
        ready();
        ready();
        assertEquals(CycleMode.HOSTING, c.mode());
        ActivityGate.Token t = gate.tryAdmit("192.168.49.10");
        s.advance(CLIENT);
        assertEquals(CycleMode.HOST_DRAINING, c.mode());
        assertEquals("draining HOST stops advertising", 1, radio.withdrawals);
        t.close();
        s.runReady();
        assertEquals(CycleMode.HOST_STOPPING, c.mode());
    }

    @Test
    public void visitDrainDoesNotWithdraw() {
        blob.set(false);
        c.start();
        s.runReady();
        ready();
        gate.tryAdmit("visit");
        s.advance(TRANSPORT);
        assertEquals(CycleMode.VISIT_DRAINING, c.mode());
        assertEquals(0, radio.withdrawals);
    }

    @Test
    public void roleLostWhileHostingStopsNow() {
        startToHosting();
        ActivityGate.Token t = gate.tryAdmit("192.168.49.10");
        AtomicBoolean aborted = new AtomicBoolean();
        t.setCancelHook(() -> aborted.set(true));
        s.advance(5_000);
        c.roleLost("hosted group lost");
        s.runReady();
        assertEquals(CycleMode.HOST_STOPPING, c.mode());
        assertTrue(aborted.get());
        assertEquals("stopHosting", radio.last().op);
        ready();
        assertEquals(CycleMode.VISIT_STARTING, c.mode());
    }

    @Test
    public void roleLostIgnoredWhileStarting() {
        c.start();
        s.runReady();
        c.roleLost("hosted group lost");
        s.runReady();
        assertEquals(CycleMode.HOST_STARTING, c.mode());
        assertEquals(List.of("startHosting"), radio.ops());
    }

    @Test
    public void retryNowLeavesUnavailableImmediately() {
        blob.set(false);
        c.start();
        s.runReady();
        for (int i = 0; i < RoleCycleController.MAX_ATTEMPTS; i++) {
            radio.last().fail("wifi off");
            s.advance(RoleCycleController.RETRY_BACKOFF_BASE_MS << i);
        }
        assertEquals(CycleMode.UNAVAILABLE, c.mode());
        int calls = radio.calls.size();
        c.retryNow(); // e.g. Wi-Fi turned on
        s.runReady();
        assertEquals(CycleMode.VISIT_STARTING, c.mode());
        assertEquals(calls + 1, radio.calls.size());
        s.advance(RoleCycleController.UNAVAILABLE_RETRY_MS); // old retry timer must be gone
        assertEquals(calls + 1, radio.calls.size());
    }

    @Test
    public void pauseAbortsWorkAndResumeRestarts() {
        startToHosting();
        ActivityGate.Token t = gate.tryAdmit("10.0.0.1");
        AtomicBoolean aborted = new AtomicBoolean();
        t.setCancelHook(() -> aborted.set(true));

        c.pause();
        s.runReady();
        assertTrue(c.isPaused());
        assertEquals(CycleMode.HOST_STOPPING, c.mode());
        assertTrue(aborted.get());
        ready();
        assertEquals(CycleMode.STOPPED, c.mode());

        int calls = radio.calls.size();
        s.advance(CLIENT * 3); // the dwell timer was cancelled; nothing happens while paused
        assertEquals(calls, radio.calls.size());

        c.resume();
        s.runReady();
        assertEquals(CycleMode.HOST_STARTING, c.mode());
    }

    @Test
    public void pauseWhileStartingIgnoresLateReady() {
        c.start();
        s.runReady();
        FakeRadio.Call start = radio.last();
        c.pause();
        s.runReady();
        assertEquals("stopHosting", radio.last().op);
        FakeRadio.Call stop = radio.last();
        start.ready();
        s.runReady();
        assertEquals(CycleMode.HOST_STOPPING, c.mode());
        stop.ready();
        s.runReady();
        assertEquals(CycleMode.STOPPED, c.mode());
    }

    @Test
    public void updateConfigAppliesAtNextDwell() {
        startToHosting();
        long deadline = c.deadlineAtMillis();
        c.updateConfig(CycleConfig.DEFAULTS.withClientPeriodMs(10_000));
        assertEquals(deadline, c.deadlineAtMillis());
        hostingToVisiting(); // switched at the original 60 s
        s.advance(TRANSPORT);
        ready();
        ready();
        assertEquals(CycleMode.HOSTING, c.mode());
        assertEquals(s.nowMillis() + 10_000, c.deadlineAtMillis());
    }

    @Test
    public void listenerSeesModesWithDeadlines() {
        startToHosting();
        assertEquals(List.of(CycleMode.HOST_STARTING, CycleMode.HOSTING), modes);
        assertEquals(CycleListener.NO_DEADLINE, (long) deadlines.get(0));
        assertEquals(c.deadlineAtMillis(), (long) deadlines.get(1));
    }

    @Test
    public void shutdownTearsDownThenReleasesScheduler() {
        startToHosting();
        c.shutdown();
        s.runReady();
        assertEquals(CycleMode.HOST_STOPPING, c.mode());
        assertFalse(s.isShutdown());
        ready();
        assertEquals(CycleMode.STOPPED, c.mode());
        assertTrue(s.isShutdown());
    }
}
