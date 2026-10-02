package net.discdd.bundledropbox.rpc;

import net.discdd.bundledropbox.cycle.ActivityGate;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Session admission/drain semantics of {@link ClientSessionTracker} against the real {@link ActivityGate}. */
public class ClientSessionTrackerTest {
    private static final String A = "192.168.49.10";
    private static final String B = "192.168.49.11";

    private ActivityGate gate;
    private RpcTestFakes.FakeClock clock;
    private RpcTestFakes.RecordingListener listener;
    private ClientSessionTracker tracker;
    private final AtomicInteger drained = new AtomicInteger();

    @Before
    public void setUp() {
        gate = new ActivityGate();
        gate.setDrainListener(drained::incrementAndGet);
        gate.openAdmissions();
        clock = new RpcTestFakes.FakeClock();
        listener = new RpcTestFakes.RecordingListener();
        tracker = new ClientSessionTracker(gate, clock, 10_000, listener);
    }

    @Test
    public void firstRpcAdmitsSessionAndTakesOneToken() {
        ClientSessionTracker.RpcTicket t1 = tracker.beginRpc(A);
        ClientSessionTracker.RpcTicket t2 = tracker.beginRpc(A);
        assertNotNull(t1);
        assertNotNull(t2);
        assertEquals(1, tracker.activeSessions());
        assertEquals("one gate token per client session", 1, gate.activeCount());
        assertEquals(1, (int) listener.sessionCounts.get(0));
    }

    @Test
    public void uploadCompletionEndsSessionAndFiresDrain() {
        tracker.beginRpc(A).end(false);           // getRecencyBlob
        tracker.beginRpc(A).end(false);           // psiExchange
        gate.closeAdmissions();                   // dwell expired mid-exchange
        ClientSessionTracker.RpcTicket upload = tracker.beginRpc(A);
        assertNotNull("admitted client continues while draining", upload);
        assertEquals(0, drained.get());
        upload.end(true);
        assertEquals(0, tracker.activeSessions());
        assertEquals(0, gate.activeCount());
        assertEquals("gate drain listener fires when last session ends", 1, drained.get());
    }

    @Test
    public void newClientRejectedWhileDraining() {
        tracker.beginRpc(A);
        gate.closeAdmissions();
        assertNull(tracker.beginRpc(B));
        assertFalse(tracker.hasSession(B));
        assertNotNull(tracker.beginRpc(A));
    }

    @Test
    public void uploadEndDoesNotEndSessionWhileOtherRpcInFlight() {
        ClientSessionTracker.RpcTicket psi = tracker.beginRpc(A);
        ClientSessionTracker.RpcTicket upload = tracker.beginRpc(A);
        upload.end(true);
        assertTrue(tracker.hasSession(A));
        psi.end(false);
        // Still there; the idle reaper ends it later.
        assertTrue(tracker.hasSession(A));
        clock.advance(10_000);
        assertEquals(1, tracker.reapIdle());
        assertFalse(tracker.hasSession(A));
    }

    @Test
    public void idleSessionReapedOnlyWithNothingInFlight() {
        ClientSessionTracker.RpcTicket longDownload = tracker.beginRpc(A);
        tracker.beginRpc(B).end(false);
        clock.advance(9_999);
        assertEquals(0, tracker.reapIdle());
        clock.advance(1);
        assertEquals("B idle 10 s", 1, tracker.reapIdle());
        assertTrue("A has an RPC in flight", tracker.hasSession(A));
        clock.advance(60_000);
        assertEquals(0, tracker.reapIdle());
        longDownload.end(false);
        clock.advance(10_000);
        assertEquals(1, tracker.reapIdle());
        assertEquals(0, gate.activeCount());
    }

    @Test
    public void ticketEndIsIdempotent() {
        ClientSessionTracker.RpcTicket a1 = tracker.beginRpc(A);
        ClientSessionTracker.RpcTicket a2 = tracker.beginRpc(A);
        a1.end(true);
        a1.end(true);
        assertTrue("second end must not decrement in-flight again", tracker.hasSession(A));
        a2.end(true);
        assertFalse(tracker.hasSession(A));
    }

    @Test
    public void forceReleaseDropsSessionsSoClientMustReadmit() {
        ClientSessionTracker.RpcTicket inFlight = tracker.beginRpc(A);
        gate.closeAdmissions();
        gate.forceReleaseAll("drain cap");
        assertFalse(tracker.hasSession(A));
        assertNull("re-admission refused while closed", tracker.beginRpc(A));
        inFlight.end(true); // late completion of the cut RPC is harmless
        assertEquals(0, gate.activeCount());
        gate.openAdmissions();
        assertNotNull(tracker.beginRpc(A));
        assertEquals(1, gate.activeCount());
    }

    @Test
    public void closeAllReleasesTokens() {
        tracker.beginRpc(A);
        tracker.beginRpc(B);
        assertEquals(2, gate.activeCount());
        gate.closeAdmissions();
        tracker.closeAll();
        assertEquals(0, tracker.activeSessions());
        assertEquals(0, gate.activeCount());
        assertEquals(1, drained.get());
        assertEquals(0, (int) listener.sessionCounts.get(listener.sessionCounts.size() - 1));
    }
}
