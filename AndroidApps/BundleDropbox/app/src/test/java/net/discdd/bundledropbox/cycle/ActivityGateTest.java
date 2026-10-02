package net.discdd.bundledropbox.cycle;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** JVM tests for {@link ActivityGate} admission, drain and force-release rules. */
public class ActivityGateTest {
    @Test
    public void startsClosedAndAdmitsWhenOpen() {
        ActivityGate g = new ActivityGate();
        assertTrue(g.isClosing());
        assertNull(g.tryAdmit("a"));
        g.openAdmissions();
        ActivityGate.Token t = g.tryAdmit("a");
        assertNotNull(t);
        assertEquals(1, g.activeCount());
        assertTrue(g.isAdmitted("a"));
    }

    @Test
    public void admittedKeyCanReenterWhileClosed() {
        ActivityGate g = new ActivityGate();
        g.openAdmissions();
        ActivityGate.Token session = g.tryAdmit("192.168.49.10");
        g.closeAdmissions();
        assertNull("new key rejected", g.tryAdmit("192.168.49.11"));
        ActivityGate.Token rpc = g.tryAdmit("192.168.49.10");
        assertNotNull("follow-on RPC admitted", rpc);
        assertNotSame(session, rpc);
        assertEquals(2, g.activeCount());
        assertEquals(1, g.activeKeyCount());

        session.close();
        rpc.close();
        assertNull("key no longer admitted once all its tokens closed", g.tryAdmit("192.168.49.10"));
    }

    @Test
    public void closeIsIdempotentAndDrainFiresOnceWhenClosed() {
        ActivityGate g = new ActivityGate();
        AtomicInteger drained = new AtomicInteger();
        g.setDrainListener(drained::incrementAndGet);
        g.openAdmissions();
        ActivityGate.Token a = g.tryAdmit("a");
        ActivityGate.Token b = g.tryAdmit("b");
        a.close();
        assertEquals("no drain while open", 0, drained.get());
        g.closeAdmissions();
        b.close();
        b.close();
        a.close();
        assertEquals(1, drained.get());
        assertEquals(0, g.activeCount());
        assertFalse(b.isValid());
    }

    @Test
    public void forceReleaseRunsHooksAndNeutralizesLateClose() {
        ActivityGate g = new ActivityGate();
        AtomicInteger drained = new AtomicInteger();
        AtomicInteger hooks = new AtomicInteger();
        g.setDrainListener(drained::incrementAndGet);
        g.openAdmissions();
        ActivityGate.Token a = g.tryAdmit("a");
        a.setCancelHook(hooks::incrementAndGet);
        g.tryAdmit("b");
        g.closeAdmissions();

        assertEquals(2, g.forceReleaseAll("cap"));
        assertEquals(1, hooks.get());
        assertEquals(0, g.activeCount());
        assertTrue(a.isCancelled());
        assertFalse(a.isValid());
        assertEquals("force release does not fire drain", 0, drained.get());

        // A new period starts; the aborted worker's late close must not disturb the new count.
        g.openAdmissions();
        ActivityGate.Token fresh = g.tryAdmit("a");
        a.close();
        assertEquals(1, g.activeCount());
        assertTrue(fresh.isValid());

        // Registering a hook on an already-cancelled token runs it immediately.
        a.setCancelHook(hooks::incrementAndGet);
        assertEquals(2, hooks.get());
    }

    @Test
    public void normalCloseDoesNotRunCancelHook() {
        ActivityGate g = new ActivityGate();
        g.openAdmissions();
        AtomicInteger hooks = new AtomicInteger();
        ActivityGate.Token t = g.tryAdmit("a");
        t.setCancelHook(hooks::incrementAndGet);
        t.close();
        g.forceReleaseAll("later");
        assertEquals(0, hooks.get());
        assertFalse(t.isCancelled());
    }
}
