package net.discdd.bundledropbox.cycle;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static net.discdd.bundledropbox.cycle.CycleHarness.CAP;
import static net.discdd.bundledropbox.cycle.CycleHarness.CLIENT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Deep-sleep timing (Audit M1/M2): {@link FakeScheduler#suspend(long)} moves the elapsedRealtime clock
 * while scheduler delays stand still, exactly like an executor on {@code System.nanoTime} during CPU
 * suspend. Deadlines must still be honored, from the clock, on the next wake-up or event.
 */
public class RoleCycleControllerSleepTest {
    private final CycleHarness h = new CycleHarness();

    @Test
    public void lateExpiryDrainsFromDetectionInsteadOfCancellingTheWakingClient() {
        h.startToHosting();
        long expiry = h.c.deadlineAtMillis();
        h.s.advance(10_000);
        h.s.suspend(100_000); // slept through the dwell end (and through expiry + cap)
        assertEquals("nothing ran while suspended", CycleMode.HOSTING, h.c.mode());

        // The client whose packets woke the CPU is admitted; the admission is an event for the controller.
        ActivityGate.Token client = h.gate.tryAdmit("192.168.49.10");
        assertNotNull(client);
        AtomicBoolean aborted = new AtomicBoolean();
        client.setCancelHook(() -> aborted.set(true));
        h.s.runReady();

        long detectedAt = h.s.nowMillis();
        assertTrue(detectedAt > expiry + CAP);
        assertEquals(CycleMode.HOST_DRAINING, h.c.mode());
        assertEquals("cap measured from detection, not the nominal expiry", detectedAt + CAP,
                     h.c.deadlineAtMillis());
        assertFalse("in-flight work is not force-cancelled by a late wake-up", aborted.get());

        h.s.advance(3_000);
        client.close(); // finished its bundle
        h.s.runReady();
        assertEquals(CycleMode.HOST_STOPPING, h.c.mode());
        assertFalse(aborted.get());
    }

    @Test
    public void stretchedTimerIsCaughtUpWithinOneSliceOfAwakeTime() {
        h.startToHosting();
        h.s.suspend(CLIENT * 10);
        // Without slicing, the executor would still need ~60 s of awake time before the dwell timer fired.
        h.s.advance(RoleCycleController.TIMER_SLICE_MS);
        assertEquals(CycleMode.HOST_STOPPING, h.c.mode());
    }

    @Test
    public void anyEventFiresAnOverdueTimerFirst() {
        h.blob.set(false);
        h.c.start();
        h.s.runReady();
        h.failAllAttempts();
        assertEquals(CycleMode.UNAVAILABLE, h.c.mode());
        int calls = h.radio.calls.size();

        h.s.suspend(RoleCycleController.UNAVAILABLE_RETRY_MS + 1);
        h.c.hostedClientsChanged(); // any posted event (a radio broadcast, an operator tap, ...)
        h.s.runReady();
        assertEquals("the overdue UNAVAILABLE retry ran on the event", calls + 1, h.radio.calls.size());
        assertEquals(CycleMode.VISIT_STARTING, h.c.mode());
    }

    @Test
    public void timersNeverFireBeforeTheirClockDeadline() {
        h.startToHosting();
        long deadline = h.c.deadlineAtMillis();
        // Slice wake-ups happen along the way; each re-arms until the clock deadline.
        h.s.advance(deadline - h.s.nowMillis() - 1);
        assertEquals(CycleMode.HOSTING, h.c.mode());
        h.c.hostedClientsChanged(); // an event just before the deadline does not fire it either
        h.s.runReady();
        assertEquals(CycleMode.HOSTING, h.c.mode());
        h.s.advance(1);
        assertEquals(CycleMode.HOST_STOPPING, h.c.mode());
    }

    @Test
    public void drainCapIsRealTimeOnceTheDrainHasBegun() {
        h.startToHosting();
        ActivityGate.Token client = h.gate.tryAdmit("192.168.49.10");
        AtomicBoolean aborted = new AtomicBoolean();
        client.setCancelHook(() -> aborted.set(true));
        h.s.advance(CLIENT);
        assertEquals(CycleMode.HOST_DRAINING, h.c.mode());

        h.s.suspend(CAP + 1_000); // the in-flight work already had the full cap of real time
        h.s.advance(RoleCycleController.TIMER_SLICE_MS);
        assertEquals(CycleMode.HOST_STOPPING, h.c.mode());
        assertTrue(aborted.get());
    }

    @Test
    public void unavailableRetryIsNotStretchedBySleep() {
        h.blob.set(false);
        h.c.start();
        h.s.runReady();
        h.failAllAttempts();
        assertEquals(CycleMode.UNAVAILABLE, h.c.mode());
        int calls = h.radio.calls.size();
        h.s.advance(1_000);
        h.s.suspend(RoleCycleController.UNAVAILABLE_RETRY_MS * 4);
        h.s.advance(RoleCycleController.TIMER_SLICE_MS); // no event at all: the next slice wake-up catches up
        assertEquals(calls + 1, h.radio.calls.size());
        assertEquals(CycleMode.VISIT_STARTING, h.c.mode());
    }
}
