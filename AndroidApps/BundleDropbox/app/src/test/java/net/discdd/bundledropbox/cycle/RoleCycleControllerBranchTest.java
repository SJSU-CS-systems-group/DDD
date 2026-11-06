package net.discdd.bundledropbox.cycle;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Fake-clock tests for controller branches the Wi-Fi audit (TC-1) found untested: a driver that throws
 * synchronously, a throwing {@code hasRecencyBlob}, a shutdown whose stop never completes, role loss on the
 * VISIT side, the fallback being re-armed once a role works, and a late visitFinished while draining.
 */
public class RoleCycleControllerBranchTest {
    private final CycleHarness h = new CycleHarness();

    /** HOSTING, then the idle switch to VISITING. */
    private void toVisiting() {
        h.startToHosting();
        h.s.advance(CycleHarness.CLIENT);
        h.ready(); // stopHosting
        assertEquals("startVisit", h.radio.last().op);
        h.ready();
        assertEquals(CycleMode.VISITING, h.c.mode());
    }

    @Test
    public void driverThrowingSynchronouslyIsRetriedLikeAFailure() {
        h.radio.throwOnNextCall = new IllegalStateException("looper quit");
        h.c.start();
        h.s.runReady();
        assertEquals("the throw is reported with the op's own generation", 1, h.errors.size());
        assertTrue(h.errors.get(0), h.errors.get(0).contains("driver threw"));
        assertEquals(CycleMode.HOST_STARTING, h.c.mode());
        assertTrue(h.radio.calls.isEmpty());

        h.s.advance(RoleCycleController.RETRY_BACKOFF_BASE_MS);
        assertEquals("backoff retry issued, cycle not stranded", List.of("startHosting"), h.radio.ops());
        h.ready();
        assertEquals(CycleMode.HOSTING, h.c.mode());
    }

    @Test
    public void throwingBlobSupplierCountsAsNoBlob() {
        h.blobError = new IllegalStateException("store closed");
        h.c.start();
        h.s.runReady();
        assertEquals("bootstrap VISIT", CycleMode.VISIT_STARTING, h.c.mode());
        h.ready();
        assertEquals(CycleMode.VISITING, h.c.mode());
        h.c.forceHost();
        h.s.runReady();
        assertEquals("no HOST without a readable blob", CycleMode.VISITING, h.c.mode());
        assertEquals(List.of("startVisit"), h.radio.ops());
    }

    @Test
    public void shutdownTimeoutFailsPendingStopFuturesAndReleasesScheduler() {
        h.startToHosting();
        CompletableFuture<Void> stopped = h.c.stop();
        h.s.runReady();
        assertEquals(CycleMode.HOST_STOPPING, h.c.mode());
        h.c.shutdown();
        h.s.runReady();
        assertEquals("the pending stop is not re-issued", List.of("startHosting", "stopHosting"), h.radio.ops());

        // The radio never answers the stop.
        h.s.advance(RoleCycleController.SHUTDOWN_TIMEOUT_MS - 1);
        assertFalse(stopped.isDone());
        assertFalse(h.s.isShutdown());
        h.s.advance(1);
        assertTrue(stopped.isCompletedExceptionally());
        assertTrue(h.s.isShutdown());
    }

    @Test
    public void roleLostWhileVisitingStopsNow() {
        toVisiting();
        ActivityGate.Token visit = h.gate.tryAdmit("visit");
        assertNotNull(visit);
        AtomicBoolean aborted = new AtomicBoolean();
        visit.setCancelHook(() -> aborted.set(true));
        h.c.roleLost("Wi-Fi Direct disabled");
        h.s.runReady();
        assertEquals(CycleMode.VISIT_STOPPING, h.c.mode());
        assertEquals("stopVisit", h.radio.last().op);
        assertTrue(aborted.get());
        assertTrue(h.errors.contains("VISITING:Wi-Fi Direct disabled"));
        h.ready();
        assertEquals(CycleMode.HOST_STARTING, h.c.mode());
    }

    @Test
    public void roleLostWhileVisitDrainingStopsNowAndDropsTheCap() {
        h.blob.set(false);
        h.c.start();
        h.s.runReady();
        h.ready();
        ActivityGate.Token visit = h.gate.tryAdmit("visit");
        h.s.advance(CycleHarness.TRANSPORT);
        assertEquals(CycleMode.VISIT_DRAINING, h.c.mode());
        h.c.roleLost("Wi-Fi Direct channel lost");
        h.s.runReady();
        assertEquals(CycleMode.VISIT_STOPPING, h.c.mode());
        assertTrue(visit.isCancelled());
        h.ready();
        assertEquals("no blob: VISIT again", CycleMode.VISIT_STARTING, h.c.mode());
        int calls = h.radio.calls.size();
        h.s.advance(CycleHarness.CAP); // the drain-cap timer must be gone
        assertEquals(CycleMode.VISIT_STARTING, h.c.mode());
        assertEquals(calls, h.radio.calls.size());
    }

    @Test
    public void fallbackIsReArmedOnceARoleWorks() {
        h.c.start();
        h.s.runReady();
        h.failAllAttempts();
        assertEquals("HOST exhausted: fall back to VISIT", CycleMode.VISIT_STARTING, h.c.mode());
        h.ready();
        assertEquals(CycleMode.VISITING, h.c.mode());
        h.s.advance(CycleHarness.TRANSPORT);
        h.ready(); // stopVisit
        assertEquals(CycleMode.HOST_STARTING, h.c.mode());
        h.failAllAttempts();
        assertEquals("VISIT worked in between: HOST may fall back again", CycleMode.VISIT_STARTING, h.c.mode());
        assertEquals("startVisit", h.radio.last().op);
    }

    @Test
    public void visitFinishedWhileVisitDrainingIsIgnored() {
        toVisiting();
        FakeRadio.Call visit = h.radio.last();
        ActivityGate.Token worker = h.gate.tryAdmit("visit");
        h.s.advance(CycleHarness.TRANSPORT);
        assertEquals(CycleMode.VISIT_DRAINING, h.c.mode());
        long cap = h.c.deadlineAtMillis();
        int calls = h.radio.calls.size();
        h.s.advance(1_000);
        visit.visitFinished(true);
        h.s.runReady();
        assertEquals(CycleMode.VISIT_DRAINING, h.c.mode());
        assertEquals("the drain cap is not re-armed", cap, h.c.deadlineAtMillis());
        assertEquals(calls, h.radio.calls.size());
        worker.close(); // the worker's token close drives the switch
        h.s.runReady();
        assertEquals(CycleMode.VISIT_STOPPING, h.c.mode());
    }
}
