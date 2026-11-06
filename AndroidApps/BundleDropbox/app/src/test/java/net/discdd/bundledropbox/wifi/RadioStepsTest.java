package net.discdd.bundledropbox.wifi;

import org.junit.Test;

import static net.discdd.bundledropbox.wifi.RadioSteps.GroupProbe.GROUP;
import static net.discdd.bundledropbox.wifi.RadioSteps.GroupProbe.NO_ANSWER;
import static net.discdd.bundledropbox.wifi.RadioSteps.GroupProbe.NO_GROUP;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JVM tests for {@link RadioSteps}: requests AOSP would answer BUSY in the current state are not sent (audit
 * WD-1), and discovery retries stop once a connect has started (audit WD-4).
 */
public class RadioStepsTest {
    @Test
    public void removeGroupIsSkippedOnlyWhenTheProbeSawNoGroup() {
        assertFalse("startVisit after stopHosting, or stopVisit while discovering", RadioSteps.needsRemoveGroup(NO_GROUP));
        assertTrue("our hosted group or a carrier's group", RadioSteps.needsRemoveGroup(GROUP));
        assertTrue("unknown state: remove anyway", RadioSteps.needsRemoveGroup(NO_ANSWER));
    }

    @Test
    public void removeGroupIsSentWhileACreateGroupMayStillBeForming() {
        assertTrue("a stop superseding a HOST start before the group formed",
                   RadioSteps.needsRemoveGroup(NO_GROUP, true));
        assertFalse("no createGroup in flight", RadioSteps.needsRemoveGroup(NO_GROUP, false));
        assertTrue(RadioSteps.needsRemoveGroup(GROUP, false));
        assertTrue(RadioSteps.needsRemoveGroup(NO_ANSWER, false));
    }

    @Test
    public void cancelConnectIsSkippedWhenTheJoinAlreadyReachedAGroup() {
        assertFalse("timed out after the group started (DHCP or GO address pending)",
                    RadioSteps.needsCancelConnect(true));
        assertTrue("no group yet: the connect may still be negotiating", RadioSteps.needsCancelConnect(false));
    }

    @Test
    public void discoveryBeforeReadyIsTheStartStepItself() {
        assertTrue(RadioSteps.discoveryWanted(true, false, false));
        assertFalse("session ended", RadioSteps.discoveryWanted(false, false, false));
    }

    @Test
    public void discoveryAfterReadyOnlyWhileIdleInDiscovering() {
        assertTrue(RadioSteps.discoveryWanted(true, true, true));
        assertFalse("a connect started (CONNECTING, EXCHANGING or leaving the carrier)",
                    RadioSteps.discoveryWanted(true, true, false));
        assertFalse("session ended", RadioSteps.discoveryWanted(false, true, true));
    }
}
