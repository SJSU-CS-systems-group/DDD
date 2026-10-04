package net.discdd.bundledropbox.wifi;

import org.junit.Test;

import static net.discdd.bundledropbox.wifi.AttemptLifecycle.RadioLossAction.CANCEL_EXCHANGE;
import static net.discdd.bundledropbox.wifi.AttemptLifecycle.RadioLossAction.FAIL_NOW;
import static net.discdd.bundledropbox.wifi.AttemptLifecycle.RadioLossAction.NONE;
import static net.discdd.bundledropbox.wifi.AttemptLifecycle.Stage.CANCELLING;
import static net.discdd.bundledropbox.wifi.AttemptLifecycle.Stage.CONNECTING;
import static net.discdd.bundledropbox.wifi.AttemptLifecycle.Stage.DONE;
import static net.discdd.bundledropbox.wifi.AttemptLifecycle.Stage.EXCHANGING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JVM tests for {@link AttemptLifecycle}, the per-carrier attempt state machine of the VISIT joiner. The key
 * invariant: a joined attempt ends only through its runner's completion (or stopVisit), so every exchange
 * that started reports {@code visitFinished} once and no runner is orphaned (audit finding i1).
 */
public class AttemptLifecycleTest {
    @Test
    public void happyPathReportsExactlyOnce() {
        AttemptLifecycle a = new AttemptLifecycle();
        assertEquals(CONNECTING, a.stage());
        assertFalse(a.isJoined());
        assertTrue(a.joined());
        assertEquals(EXCHANGING, a.stage());
        assertFalse("duplicate CONNECTION_CHANGED must not start a second runner", a.joined());
        assertTrue(a.exchangeDone());
        assertTrue(a.isDone());
        assertFalse("visitFinished at most once", a.exchangeDone());
    }

    @Test
    public void failedJoinEndsTheAttemptOnceWithoutVisitFinished() {
        AttemptLifecycle a = new AttemptLifecycle();
        assertTrue(a.failJoin());        // connect error
        assertFalse(a.failJoin());       // the CONNECT_MS timeout firing late
        assertFalse(a.joined());         // a late "joined" broadcast
        assertFalse(a.exchangeDone());   // no exchange ran: no visitFinished
        assertEquals(DONE, a.stage());
    }

    @Test
    public void joinFailureSignalsCannotEndARunningExchange() {
        AttemptLifecycle a = new AttemptLifecycle();
        a.joined();
        assertFalse("a late timeout must not orphan the runner", a.failJoin());
        assertEquals(EXCHANGING, a.stage());
    }

    @Test
    public void radioLostWhileConnectingFailsNow() {
        AttemptLifecycle a = new AttemptLifecycle();
        assertEquals(FAIL_NOW, a.radioLost());
        assertTrue(a.isDone());
        assertEquals(NONE, a.radioLost());
    }

    @Test
    public void radioLostWhileExchangingCancelsAndStillReportsWhenRunnerReturns() {
        AttemptLifecycle a = new AttemptLifecycle();
        a.joined();
        assertEquals(CANCEL_EXCHANGE, a.radioLost());
        assertEquals(CANCELLING, a.stage());
        assertTrue("still joined: stopVisit must keep waiting for the runner", a.isJoined());
        assertFalse(a.isDone());
        assertEquals("a second loss event does nothing more", NONE, a.radioLost());
        assertFalse(a.failJoin());
        assertTrue("the runner's completion reports visitFinished", a.exchangeDone());
        assertTrue(a.isDone());
    }

    @Test
    public void sessionEndSilencesEverything() {
        AttemptLifecycle a = new AttemptLifecycle();
        a.joined();
        a.end(); // stopVisit takes over and waits for the runner itself
        assertTrue(a.isDone());
        assertFalse(a.exchangeDone());
        assertEquals(NONE, a.radioLost());
    }
}
