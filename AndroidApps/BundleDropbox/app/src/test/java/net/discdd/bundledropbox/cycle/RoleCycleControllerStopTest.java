package net.discdd.bundledropbox.cycle;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.discdd.bundledropbox.cycle.CycleHarness.CLIENT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Stop/pause semantics (Audit m4, m15, m22): a real stop API with completion, pause reporting for
 * persistence, and a halt that never assumes a radio is idle when a stop or start failed.
 */
public class RoleCycleControllerStopTest {
    private final CycleHarness h = new CycleHarness();

    /** HOSTING → roleLost → STOP_HOST fails every attempt → UNAVAILABLE with a failed stop. */
    private void toUnavailableWithFailedStop() {
        h.startToHosting();
        h.c.roleLost("hosted group lost");
        h.s.runReady();
        assertEquals("stopHosting", h.radio.last().op);
        h.failAllAttempts();
        assertEquals(CycleMode.UNAVAILABLE, h.c.mode());
    }

    @Test
    public void stopThatFailsThreeTimesGoesUnavailableThenIsRetried() {
        toUnavailableWithFailedStop();
        assertTrue(h.gate.isClosing());
        h.s.advance(RoleCycleController.UNAVAILABLE_RETRY_MS);
        assertEquals("the failed STOP is retried, not skipped", "stopHosting", h.radio.last().op);
        h.ready();
        assertEquals(CycleMode.VISIT_STARTING, h.c.mode());
    }

    @Test
    public void forceHostAfterFailedStopRetriesTheStopFirst() {
        toUnavailableWithFailedStop();
        h.c.forceHost();
        h.s.runReady();
        assertEquals("stopHosting", h.radio.last().op);
        h.ready();
        assertEquals("the requested role follows the stop", CycleMode.HOST_STARTING, h.c.mode());
    }

    @Test
    public void pauseWhileUnavailableMakesOneBestEffortStop() {
        toUnavailableWithFailedStop();
        int calls = h.radio.calls.size();
        h.c.pause();
        h.s.runReady();
        assertEquals("not STOPPED with the group possibly still up", CycleMode.HOST_STOPPING, h.c.mode());
        assertEquals(calls + 1, h.radio.calls.size());
        h.ready();
        assertEquals(CycleMode.STOPPED, h.c.mode());

        h.c.resume();
        h.s.runReady();
        assertEquals("radio known clean: start directly", "startHosting", h.radio.last().op);
    }

    @Test
    public void pauseWhileUnavailableAfterFailedStartStopsThatRole() {
        h.blob.set(false);
        h.c.start();
        h.s.runReady();
        h.failAllAttempts();
        assertEquals(CycleMode.UNAVAILABLE, h.c.mode());
        h.c.pause();
        h.s.runReady();
        assertEquals("stopVisit", h.radio.last().op);
        h.ready();
        assertEquals(CycleMode.STOPPED, h.c.mode());
    }

    @Test
    public void failedHaltStopIsRememberedAndRunBeforeTheNextStart() {
        toUnavailableWithFailedStop();
        h.c.pause();
        h.s.runReady();
        h.fail("still busy"); // the one best-effort stop fails too: do not loop
        assertEquals(CycleMode.STOPPED, h.c.mode());
        int calls = h.radio.calls.size();
        h.s.advance(RoleCycleController.UNAVAILABLE_RETRY_MS * 2);
        assertEquals("no retries while paused", calls, h.radio.calls.size());

        h.c.resume();
        h.s.runReady();
        assertEquals("dirty HOST is stopped before the first role", "stopHosting", h.radio.last().op);
        h.ready();
        assertEquals("then the normal first role (blob cached: HOST)", "startHosting", h.radio.last().op);
    }

    @Test
    public void dirtyRoleStillSkipsHostWithoutBlob() {
        h.startToHosting();
        h.c.pause();
        h.s.runReady();
        h.fail("busy");
        assertEquals(CycleMode.STOPPED, h.c.mode());
        h.blob.set(false);
        h.c.resume();
        h.s.runReady();
        assertEquals("stopHosting", h.radio.last().op);
        h.ready();
        assertEquals("bootstrap rule still applies", "startVisit", h.radio.last().op);
    }

    @Test
    public void shutdownRetriesADirtyStopOnce() {
        h.startToHosting();
        h.c.pause();
        h.s.runReady();
        h.fail("busy");
        assertEquals(CycleMode.STOPPED, h.c.mode());
        h.c.shutdown();
        h.s.runReady();
        assertEquals("stopHosting", h.radio.last().op);
        assertFalse(h.s.isShutdown());
        h.ready();
        assertEquals(CycleMode.STOPPED, h.c.mode());
        assertTrue(h.s.isShutdown());
    }

    @Test
    public void roleLostDuringHostDrainingStopsNow() {
        h.startToHosting();
        ActivityGate.Token t = h.gate.tryAdmit("192.168.49.10");
        AtomicBoolean aborted = new AtomicBoolean();
        t.setCancelHook(() -> aborted.set(true));
        h.s.advance(CLIENT);
        assertEquals(CycleMode.HOST_DRAINING, h.c.mode());
        h.c.roleLost("hosted group lost");
        h.s.runReady();
        assertEquals(CycleMode.HOST_STOPPING, h.c.mode());
        assertTrue(aborted.get());
        h.ready();
        assertEquals(CycleMode.VISIT_STARTING, h.c.mode());
    }

    @Test
    public void stopCompletesWhenTheRadioIsIdleAndCanBeRestarted() {
        h.startToHosting();
        CompletableFuture<Void> stopped = h.c.stop();
        h.s.runReady();
        assertFalse(h.c.isRunning());
        assertEquals(CycleMode.HOST_STOPPING, h.c.mode());
        assertFalse(stopped.isDone());
        h.ready();
        assertEquals(CycleMode.STOPPED, h.c.mode());
        assertTrue(stopped.isDone());
        assertFalse(stopped.isCompletedExceptionally());
        assertFalse("stop is not pause", h.c.isPaused());

        h.c.start();
        h.s.runReady();
        assertTrue(h.c.isRunning());
        assertEquals(CycleMode.HOST_STARTING, h.c.mode());
    }

    @Test
    public void stopCompletesEvenIfTheRadioRefusesToStop() {
        h.startToHosting();
        CompletableFuture<Void> stopped = h.c.stop();
        h.s.runReady();
        h.fail("busy");
        assertEquals(CycleMode.STOPPED, h.c.mode());
        assertTrue(stopped.isDone());
    }

    @Test
    public void stopWhenAlreadyStoppedCompletesAtOnce() {
        CompletableFuture<Void> stopped = h.c.stop();
        h.s.runReady();
        assertTrue(stopped.isDone());
        assertTrue(h.radio.calls.isEmpty());
    }

    @Test
    public void startOverrulesAStopStillInProgress() {
        h.startToHosting();
        CompletableFuture<Void> stopped = h.c.stop();
        h.s.runReady();
        h.c.start();
        h.s.runReady();
        assertTrue(stopped.isCancelled());
        h.ready(); // stopHosting done: running again, so the cycle continues
        assertEquals(CycleMode.VISIT_STARTING, h.c.mode());
    }

    @Test
    public void stopAfterShutdownFailsInsteadOfHanging() {
        h.c.shutdown();
        h.s.runReady();
        assertTrue(h.s.isShutdown());
        CompletableFuture<Void> stopped = h.c.stop();
        assertTrue(stopped.isCompletedExceptionally());
    }

    @Test
    public void pauseIsReportedForPersistenceAndRestoredBeforeStart() {
        // Service restart with a persisted pause: pause() before start() keeps the radio idle.
        h.c.pause();
        h.c.start();
        h.s.runReady();
        assertEquals(CycleMode.STOPPED, h.c.mode());
        assertTrue(h.radio.calls.isEmpty());
        assertTrue(h.c.isPaused());
        assertEquals(List.of(true), h.pauseReports);

        h.c.resume();
        h.s.runReady();
        assertEquals(List.of(true, false), h.pauseReports);
        assertEquals(CycleMode.HOST_STARTING, h.c.mode());

        h.c.resume(); // no change: not reported again
        h.s.runReady();
        assertEquals(List.of(true, false), h.pauseReports);
    }
}
