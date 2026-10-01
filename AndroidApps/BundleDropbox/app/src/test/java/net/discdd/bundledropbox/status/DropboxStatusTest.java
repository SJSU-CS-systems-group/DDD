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
        DropboxStatus a = DropboxStatus.builder().advertisedId("abc-1").recencyBlobTimestamp(42).build();
        DropboxStatus b = a.toBuilder()
                .cycle(CycleMode.HOSTING, 1_000, 61_000)
                .storeStats(new StoreStats(2, 200, 3, 300, 0, 1024))
                .lastVisit(new DropboxStatus.LastVisit("carrier", 2, 3, 99, true))
                .build();
        assertEquals("abc-1", b.advertisedId());
        assertEquals(42, b.recencyBlobTimestamp());
        assertEquals(CycleMode.HOSTING, b.mode());
        assertEquals(61_000, b.deadlineAtMillis());
        assertEquals(3, b.downstreamCount());
        assertEquals(300, b.downstreamBytes());
        assertTrue(b.lastVisit().ok());
        assertEquals(CycleMode.STOPPED, a.mode());
        assertEquals(b, b.toBuilder().build());
    }
}
