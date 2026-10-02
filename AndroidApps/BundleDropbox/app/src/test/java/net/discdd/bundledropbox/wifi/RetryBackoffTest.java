package net.discdd.bundledropbox.wifi;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** JVM tests for {@link RetryBackoff} (BUSY retries) and {@link P2pReasons}. */
public class RetryBackoffTest {
    @Test
    public void busyPolicyDoublesThenCaps() {
        RetryBackoff b = RetryBackoff.BUSY;
        assertEquals(250, b.delayBeforeRetry(0));
        assertEquals(500, b.delayBeforeRetry(1));
        assertEquals(1_000, b.delayBeforeRetry(2));
        assertEquals(2_000, b.delayBeforeRetry(3));
        assertEquals(2_000, b.delayBeforeRetry(5));
        assertEquals(-1, b.delayBeforeRetry(6));
        assertEquals(-1, b.delayBeforeRetry(-1));
    }

    @Test
    public void busyRetriesFitInsideTheShortestStepTimeout() {
        long total = 0;
        for (int i = 0; i < RetryBackoff.BUSY.maxRetries(); i++) total += RetryBackoff.BUSY.delayBeforeRetry(i);
        assertTrue(total < WifiTimeouts.REMOVAL_MS);
    }

    @Test
    public void zeroRetriesNeverRetries() {
        assertEquals(-1, new RetryBackoff(100, 100, 0).delayBeforeRetry(0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvertedBounds() {
        new RetryBackoff(500, 100, 3);
    }

    @Test
    public void describesReasonCodes() {
        assertEquals("BUSY (2)", P2pReasons.describe(P2pReasons.BUSY));
        assertEquals("ERROR (0)", P2pReasons.describe(0));
        assertEquals("P2P_UNSUPPORTED (1)", P2pReasons.describe(1));
        assertEquals("NO_SERVICE_REQUESTS (3)", P2pReasons.describe(3));
        assertEquals("UNKNOWN (9)", P2pReasons.describe(9));
    }
}
