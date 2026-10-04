package net.discdd.bundledropbox.service;

import net.discdd.bundledropbox.cycle.ActivityGate;
import net.discdd.bundledropbox.visit.VisitResult;

import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * JVM tests for {@link VisitCoordinator}, the service glue between the joiner's {@code VisitRunner} and the
 * role cycle's {@link ActivityGate} (audit m19, m1, i2).
 */
public class VisitCoordinatorTest {
    private static final InetAddress GO = InetAddress.getLoopbackAddress();

    private ActivityGate gate;
    private FakeExchange exchange;
    private boolean blobStored;
    private final List<String> events = new ArrayList<>();
    private final AtomicInteger drains = new AtomicInteger();
    private VisitCoordinator visits;

    /** Scriptable exchange: records what it saw, returns {@link #result}, can run an action mid-visit. */
    private final class FakeExchange implements VisitCoordinator.Exchange {
        VisitResult result = ok(true);
        Runnable during = () -> { };
        RuntimeException toThrow;
        int calls;
        int cancels;
        boolean keepGoingAtStart;
        BooleanSupplier cancelled;
        int activeDuring = -1;

        @Override
        public VisitResult visit(InetAddress host, int port, String carrierId, BooleanSupplier keepGoing,
                                 BooleanSupplier cancelled) {
            calls++;
            this.cancelled = cancelled;
            keepGoingAtStart = keepGoing.getAsBoolean();
            activeDuring = gate.activeCount();
            during.run();
            if (toThrow != null) throw toThrow;
            return result;
        }

        @Override
        public void cancel() {
            cancels++;
        }
    }

    private static VisitResult ok(boolean recencyFetched) {
        return new VisitResult("T1", true, recencyFetched, false, 1, 2, 0, 0, false, null);
    }

    @Before
    public void setUp() {
        gate = new ActivityGate();
        gate.setDrainListener(drains::incrementAndGet);
        exchange = new FakeExchange();
        blobStored = true;
        visits = new VisitCoordinator(() -> gate, () -> exchange, () -> blobStored, new VisitCoordinator.Events() {
            @Override
            public void exchangeStarting(String carrierId) {
                events.add("start " + carrierId);
            }

            @Override
            public void visitResult(VisitResult result) {
                events.add("result ok=" + result.ok());
            }

            @Override
            public void exchangeEnded() {
                events.add("end");
            }

            @Override
            public void visitSkipped(String carrierId, String why) {
                events.add("skip " + carrierId);
            }
        });
    }

    @Test
    public void oneTokenCoversTheWholeExchange() throws Exception {
        gate.openAdmissions();
        assertTrue(visits.runVisit(GO, "T1", () -> false));
        assertEquals("token held while visiting", 1, exchange.activeDuring);
        assertTrue("keepGoing while admissions are open", exchange.keepGoingAtStart);
        assertEquals(0, gate.activeCount());
        assertEquals(List.of("start T1", "result ok=true", "end"), events);
    }

    @Test
    public void earlyReturnNeedsOkRecencyFetchedAndAStoredBlob() throws Exception {
        gate.openAdmissions();
        // Review #9: a carrier that never synced (no SUCCESS blob) must not end VISIT early.
        exchange.result = ok(false);
        assertFalse(visits.runVisit(GO, "T1", () -> false));
        // Audit i2: fetched but not stored (offer failed or the verifier rejected it) during bootstrap.
        exchange.result = ok(true);
        blobStored = false;
        assertFalse(visits.runVisit(GO, "T1", () -> false));
        // Incomplete exchange.
        blobStored = true;
        exchange.result = new VisitResult("T1", true, true, true, 0, 0, 0, 1, false, "upload failed");
        assertFalse(visits.runVisit(GO, "T1", () -> false));
        exchange.result = ok(true);
        assertTrue(visits.runVisit(GO, "T1", () -> false));
    }

    @Test
    public void closedGateWithoutJoinTokenSkipsTheExchange() throws Exception {
        assertFalse(visits.runVisit(GO, "T1", () -> false));
        assertEquals(0, exchange.calls);
        assertEquals(List.of("skip T1"), events);
    }

    @Test
    public void joinTokenKeepsTheDrainOpenUntilTheExchangeEnds() throws Exception {
        gate.openAdmissions();
        assertTrue(visits.beginJoin("T1"));
        // The VISIT dwell expires while the join is still in progress.
        gate.closeAdmissions();
        assertEquals("the join holds the drain open", 1, gate.activeCount());
        assertEquals(0, drains.get());

        // The join completes: the exchange reuses the join token although admissions are closed, fetches the
        // recency blob and stops before the first bundle (keepGoing is false).
        exchange.result = new VisitResult("T1", true, true, true, 0, 0, 0, 0, true, null);
        assertFalse("stopped early: not a success", visits.runVisit(GO, "T1", () -> false));
        assertEquals(1, exchange.calls);
        assertEquals("same single token", 1, exchange.activeDuring);
        assertFalse("dwell over: finish after the recency fetch", exchange.keepGoingAtStart);
        assertEquals("drain completes when the exchange ends", 1, drains.get());
        assertFalse(visits.hasPendingJoin());
    }

    @Test
    public void beginJoinIsRefusedOnceAdmissionsAreClosed() {
        assertFalse(visits.beginJoin("T1"));
        assertEquals(0, gate.activeCount());
    }

    @Test
    public void endJoinReleasesAFailedAttemptAndCompletesTheDrain() {
        gate.openAdmissions();
        assertTrue(visits.beginJoin("T1"));
        gate.closeAdmissions();
        visits.endJoin("OTHER"); // a different attempt's end must not release this one
        assertEquals(1, gate.activeCount());
        visits.endJoin("T1");
        assertEquals(0, gate.activeCount());
        assertEquals(1, drains.get());
        visits.endJoin("T1"); // idempotent
        assertEquals(1, drains.get());
    }

    @Test
    public void aSecondBeginJoinReplacesAStaleOne() {
        gate.openAdmissions();
        assertTrue(visits.beginJoin("T1"));
        assertTrue(visits.beginJoin("T2"));
        assertEquals("only the newest attempt holds a token", 1, gate.activeCount());
    }

    @Test
    public void aLeftoverJoinTokenDoesNotAdmitANewJoinAfterTheDwellEnds() {
        // The gate admits follow-on tokens for a key that still has a live token, even when closed. A leftover
        // join token (endJoin never called) must be released first, or a new join would sneak past the drain.
        gate.openAdmissions();
        assertTrue(visits.beginJoin("T1"));
        gate.closeAdmissions();
        assertFalse(visits.beginJoin("T2"));
        assertEquals(0, gate.activeCount());
        assertEquals("releasing the leftover completed the drain", 1, drains.get());
    }

    @Test
    public void forceReleaseDuringTheJoinSkipsTheExchange() throws Exception {
        gate.openAdmissions();
        assertTrue(visits.beginJoin("T1"));
        gate.closeAdmissions();
        gate.forceReleaseAll("drain cap");
        assertFalse(visits.runVisit(GO, "T1", () -> false));
        assertEquals(0, exchange.calls);
    }

    @Test
    public void drainCapForceReleaseCancelsTheRunningExchange() throws Exception {
        gate.openAdmissions();
        AtomicBoolean cancelledSeen = new AtomicBoolean();
        exchange.during = () -> {
            gate.closeAdmissions();
            gate.forceReleaseAll("drain cap");
            cancelledSeen.set(exchange.cancelled.getAsBoolean());
        };
        exchange.result = new VisitResult("T1", true, true, false, 0, 0, 0, 1, true, "cancelled");
        assertFalse(visits.runVisit(GO, "T1", () -> false));
        assertEquals("cancel hook hard-cancels the gRPC channel", 1, exchange.cancels);
        assertTrue("the visit sees the cancellation", cancelledSeen.get());
        assertEquals(0, gate.activeCount());
    }

    @Test
    public void aThrowingExchangeStillReleasesTheToken() {
        gate.openAdmissions();
        exchange.toThrow = new IllegalStateException("boom");
        try {
            visits.runVisit(GO, "T1", () -> false);
            fail("expected the exchange's exception");
        } catch (IllegalStateException expected) {
            // the driver treats a throw as a failed visit
        }
        assertEquals(0, gate.activeCount());
        assertEquals("end", events.get(events.size() - 1));
    }

    @Test
    public void stopRequestedEndsTheExchangeAtTheNextBundle() throws Exception {
        gate.openAdmissions();
        visits.runVisit(GO, "T1", () -> true);
        assertFalse(exchange.keepGoingAtStart);
    }

    @Test
    public void notWiredSkipsAndReleasesAPendingJoin() throws Exception {
        gate.openAdmissions();
        assertTrue(visits.beginJoin("T1"));
        exchange = null; // visit client not built (init failed)
        assertFalse(visits.runVisit(GO, "T1", () -> false));
        assertEquals(0, gate.activeCount());
    }
}
