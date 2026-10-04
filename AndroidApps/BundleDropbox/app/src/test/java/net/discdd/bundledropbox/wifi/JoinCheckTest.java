package net.discdd.bundledropbox.wifi;

import org.junit.Test;

import static net.discdd.bundledropbox.wifi.JoinCheck.Verdict.BECAME_GO;
import static net.discdd.bundledropbox.wifi.JoinCheck.Verdict.JOINED;
import static net.discdd.bundledropbox.wifi.JoinCheck.Verdict.WAIT;
import static net.discdd.bundledropbox.wifi.JoinCheck.Verdict.WRONG_GO;
import static org.junit.Assert.assertEquals;

/**
 * JVM tests for {@link JoinCheck}: the joiner accepts a connection only as a client of the carrier it asked
 * for, like the stock client's {@code sameAddressAs} check, and never as group owner.
 */
public class JoinCheckTest {
    private static final String CARRIER = "aa:bb:cc:dd:ee:ff";

    @Test
    public void waitsUntilGroupFormedAndGoAddressKnown() {
        assertEquals(WAIT, JoinCheck.evaluate(false, false, false, null, CARRIER));
        assertEquals(WAIT, JoinCheck.evaluate(true, false, false, CARRIER, CARRIER));
        assertEquals(WAIT, JoinCheck.evaluate(false, false, true, CARRIER, CARRIER));
    }

    @Test
    public void becomingGroupOwnerFailsEvenForTheRightGroup() {
        assertEquals(BECAME_GO, JoinCheck.evaluate(true, true, true, CARRIER, CARRIER));
        assertEquals(BECAME_GO, JoinCheck.evaluate(true, true, true, null, CARRIER));
    }

    @Test
    public void wrongOwnerFails() {
        assertEquals(WRONG_GO, JoinCheck.evaluate(true, false, true, "11:22:33:44:55:66", CARRIER));
    }

    @Test
    public void rightOwnerJoinsIgnoringMacCase() {
        assertEquals(JOINED, JoinCheck.evaluate(true, false, true, CARRIER.toUpperCase(), CARRIER));
    }

    @Test
    public void pollingFallbackWithoutGroupChecksOnlyThatWeAreNotGo() {
        // requestConnectionInfo has no WifiP2pGroup, so the owner cannot be compared.
        assertEquals(JOINED, JoinCheck.evaluate(true, false, true, null, CARRIER));
    }
}
