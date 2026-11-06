package net.discdd.bundledropbox.wifi;

import net.discdd.bundledropbox.cycle.RoleCycleController;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * JVM tests for the stop bounds in {@link WifiTimeouts} that service teardown waits on (audit WD-2). Each sum
 * restates the step sequence of the stop it bounds, so a step added there must be added here too.
 */
public class WifiTimeoutsTest {
    @Test
    public void removalProbeFitsInsideTheRemovalBudget() {
        // The probe (pollGroup with POLL_INTERVAL_MS, watchdog one interval later) must leave time for removeGroup.
        assertTrue(2 * WifiTimeouts.POLL_INTERVAL_MS < WifiTimeouts.REMOVAL_MS);
        assertEquals(WifiTimeouts.REMOVAL_MS + 2 * WifiTimeouts.POLL_INTERVAL_MS, WifiTimeouts.REMOVAL_WORST_MS);
    }

    @Test
    public void stopBoundCoversTheWholeVisitStop() {
        long[] visitStop = {
                WifiTimeouts.SERVICE_OP_MS,    // cancelConnect (only while a connect may be pending)
                WifiTimeouts.SERVICE_OP_MS,    // stopPeerDiscovery
                WifiTimeouts.SERVICE_OP_MS,    // clearServiceRequests
                WifiTimeouts.REMOVAL_WORST_MS, // probe + removeGroup + removal poll
                WifiTimeouts.RUNNER_EXIT_MS,   // cancelled runner
        };
        assertTrue(WifiTimeouts.STOP_WORST_MS > sum(visitStop));
    }

    @Test
    public void stopBoundCoversTheWholeHostStop() {
        long[] hostStop = {
                WifiTimeouts.SERVICE_OP_MS,    // clearLocalServices
                WifiTimeouts.ENDPOINT_MS,      // endpoint stop on the worker
                WifiTimeouts.REMOVAL_WORST_MS, // probe + removeGroup + removal poll
        };
        assertTrue(WifiTimeouts.STOP_WORST_MS > sum(hostStop));
    }

    @Test
    public void busyRetriesNeverOutlastAStepTimeout() {
        long total = 0;
        for (int i = 0; i < RetryBackoff.BUSY.maxRetries(); i++) total += RetryBackoff.BUSY.delayBeforeRetry(i);
        assertTrue(total < WifiTimeouts.SERVICE_OP_MS);
    }

    @Test
    public void teardownWaitsLongerThanTheControllerDoes() {
        // Why teardown also waits on the driver: the controller gives up (drops its scheduler) much earlier.
        assertTrue(WifiTimeouts.STOP_WORST_MS > RoleCycleController.SHUTDOWN_TIMEOUT_MS);
        assertEquals(46_500L, WifiTimeouts.STOP_WORST_MS);
    }

    private static long sum(long[] steps) {
        long total = 0;
        for (long s : steps) total += s;
        return total;
    }
}
