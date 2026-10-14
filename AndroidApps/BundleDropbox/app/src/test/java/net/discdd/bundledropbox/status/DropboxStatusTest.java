package net.discdd.bundledropbox.status;

import net.discdd.bundledropbox.cycle.CycleListener;
import net.discdd.bundledropbox.cycle.CycleMode;
import net.discdd.bundledropbox.store.StoreStats;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** JVM tests for the immutable {@link DropboxStatus} snapshot and its builder. */
public class DropboxStatusTest {
    @Test
    public void initialIsStoppedWithNoDeadline() {
        DropboxStatus s = DropboxStatus.INITIAL;
        assertEquals(CycleMode.STOPPED, s.mode());
        assertEquals(CycleListener.NO_DEADLINE, s.deadlineAtMillis());
        assertFalse(s.hasDeadline());
        assertFalse(s.hasRecencyBlob());
        assertNull(s.lastVisit());
    }

    @Test
    public void toBuilderChangesOnlyRequestedFields() {
        DropboxStatus a = DropboxStatus.builder().advertisedId("abc").ownSenderId("sender").recencyBlobTimestamp(42)
                .build();
        DropboxStatus b = a.toBuilder()
                .cycle(CycleMode.HOSTING, 1_000, 61_000)
                .storeStats(new StoreStats(2, 200, 3, 300, 0, 1024))
                .lastVisit(new DropboxStatus.LastVisit("carrier", 2, 3, 99, true))
                .build();
        assertEquals("abc", b.advertisedId());
        assertEquals("sender", b.ownSenderId());
        assertEquals(42, b.recencyBlobTimestamp());
        assertEquals(CycleMode.HOSTING, b.mode());
        assertEquals(61_000, b.deadlineAtMillis());
        assertEquals(3, b.downstreamCount());
        assertEquals(300, b.downstreamBytes());
        assertTrue(b.lastVisit().ok());
        assertEquals(CycleMode.STOPPED, a.mode());
        assertEquals(b, b.toBuilder().build());
    }

    @Test
    public void controlStatusCarriesTheRecencyExchange() {
        ControlStatus base = new ControlStatus(true, "client", "built-in", 1, 10, 0, 0, 0, 0, 0, 0, null);
        ControlStatus waiting = base.withRecency(3, 500, 2, 400, 0);
        assertTrue(waiting.recencyOutstanding());
        assertEquals(3, waiting.recencyRequestSeq());
        assertEquals(500, waiting.recencyRequestedAtMillis());
        assertEquals(2, waiting.recencyAnsweredSeq());
        assertEquals(400, waiting.recencyGrantAtMillis());
        assertEquals("client", waiting.endpointClientId());
        assertEquals(1, waiting.lastReportSeq());
        assertFalse(base.equals(waiting));
        assertEquals(waiting, base.withRecency(3, 500, 2, 400, 0));
        assertFalse(base.withRecency(3, 500, 3, 600, 0).recencyOutstanding());
        ControlStatus abandoned = base.withRecency(3, 500, 3, 400, 700);
        assertEquals(700, abandoned.recencyAbandonedAtMillis());
        assertFalse(abandoned.equals(base.withRecency(3, 500, 3, 400, 0)));
        DropboxStatus s = DropboxStatus.builder().control(waiting).build();
        assertTrue(s.control().recencyOutstanding());
    }
}
