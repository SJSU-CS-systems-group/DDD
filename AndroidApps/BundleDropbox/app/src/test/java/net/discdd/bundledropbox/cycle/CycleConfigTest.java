package net.discdd.bundledropbox.cycle;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM tests for the {@link CycleConfig} value class (defaults from 02 Decisions, validation, withers). */
public class CycleConfigTest {
    @Test
    public void defaultsMatchDecisions() {
        CycleConfig c = CycleConfig.DEFAULTS;
        assertEquals(60_000L, c.clientPeriodMs());
        assertEquals(30_000L, c.transportPeriodMs());
        assertEquals(15_000L, c.drainCapMs());
        assertTrue(c.returnAfterVisit());
    }

    @Test
    public void withersCopyAndKeepOtherFields() {
        CycleConfig c = CycleConfig.DEFAULTS.withClientPeriodMs(5_000).withReturnAfterVisit(false);
        assertEquals(5_000L, c.clientPeriodMs());
        assertEquals(30_000L, c.transportPeriodMs());
        assertFalse(c.returnAfterVisit());
        assertEquals(60_000L, CycleConfig.DEFAULTS.clientPeriodMs());
        assertEquals(c, new CycleConfig(5_000, 30_000, 15_000, false));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZeroDwell() {
        new CycleConfig(0, 30_000, 15_000, true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeDrainCap() {
        CycleConfig.DEFAULTS.withDrainCapMs(-1);
    }
}
