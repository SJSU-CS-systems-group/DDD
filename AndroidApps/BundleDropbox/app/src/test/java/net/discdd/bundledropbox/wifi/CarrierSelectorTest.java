package net.discdd.bundledropbox.wifi;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * JVM tests for {@link CarrierSelector}: first-discovered wins, each carrier at most once per period,
 * least-recently-visited rotation across periods, stale sightings ignored.
 */
public class CarrierSelectorTest {
    @Test
    public void picksFirstDiscoveredWhenNoneVisited() {
        CarrierSelector sel = new CarrierSelector();
        sel.beginPeriod();
        assertTrue(sel.observe("A", "aa:aa", "Phone A", 0, 1_000));
        assertTrue(sel.observe("B", "bb:bb", "Phone B", 0, 1_100));
        assertFalse(sel.observe("A", "aa:aa", "Phone A", 5, 1_200)); // repeat sighting
        assertEquals("A", sel.next(1_300).carrierId());
    }

    @Test
    public void triesEachCarrierOncePerPeriodThenKeepsLookingForOthers() {
        CarrierSelector sel = new CarrierSelector();
        sel.beginPeriod();
        sel.observe("A", "aa:aa", "A", 0, 0);
        sel.markAttempted("A"); // e.g. connect failed
        assertNull(sel.next(10));
        sel.observe("B", "bb:bb", "B", 0, 20); // discovered later in the same period
        assertEquals("B", sel.next(30).carrierId());
        sel.recordVisit("B", 40, true);
        assertNull(sel.next(50));
        List<DiscoveredCarrier> snap = sel.snapshot();
        assertEquals(2, snap.size());
        assertTrue(snap.get(0).attemptedThisPeriod());
        assertTrue(snap.get(1).lastVisitOk());
    }

    @Test
    public void rotatesToLeastRecentlyVisitedAcrossPeriods() {
        CarrierSelector sel = new CarrierSelector();
        sel.beginPeriod();
        sel.observe("A", "aa:aa", "A", 0, 0);
        sel.observe("B", "bb:bb", "B", 0, 1);
        assertEquals("A", sel.next(2).carrierId());
        sel.recordVisit("A", 3, true);

        sel.beginPeriod(); // next VISIT period: A seen first again, but B was never visited
        sel.observe("A", "aa:aa", "A", 0, 100);
        sel.observe("B", "bb:bb", "B", 0, 101);
        assertEquals("B", sel.next(102).carrierId());
        sel.recordVisit("B", 103, true);

        sel.beginPeriod(); // A visited at 3, B at 103: A is least recently visited
        sel.observe("B", "bb:bb", "B", 0, 200);
        sel.observe("A", "aa:aa", "A", 0, 201);
        assertEquals("A", sel.next(202).carrierId());
    }

    @Test
    public void singleCarrierIsRevisitedEveryPeriod() {
        CarrierSelector sel = new CarrierSelector();
        for (int period = 0; period < 3; period++) {
            sel.beginPeriod();
            sel.observe("A", "aa:aa", "A", 0, period * 100L);
            assertEquals("A", sel.next(period * 100L + 1).carrierId());
            sel.recordVisit("A", period * 100L + 2, period != 1);
        }
    }

    @Test
    public void staleSightingsAreNotCandidates() {
        CarrierSelector sel = new CarrierSelector();
        sel.beginPeriod();
        sel.observe("A", "aa:aa", "A", 0, 0);
        assertNull(sel.next(CarrierSelector.SEEN_TTL_MS + 1));
        sel.observe("A", "aa:aa", "A", 0, CarrierSelector.SEEN_TTL_MS + 2);
        assertEquals("A", sel.next(CarrierSelector.SEEN_TTL_MS + 3).carrierId());
    }

    @Test
    public void beginPeriodForgetsSightingsButKeepsHistory() {
        CarrierSelector sel = new CarrierSelector();
        sel.beginPeriod();
        sel.observe("A", "aa:aa", "A", 7, 0);
        sel.recordVisit("A", 5, false);
        sel.beginPeriod();
        assertTrue(sel.snapshot().isEmpty());
        assertNull(sel.next(10));
        sel.observe("A", "aa:aa", "A", 7, 20);
        DiscoveredCarrier a = sel.next(21);
        assertEquals(5, a.lastVisitedAtMillis());
        assertFalse(a.lastVisitOk());
        assertFalse(a.attemptedThisPeriod());
        assertEquals(7, a.recencyBlobTimestamp());
    }

    @Test
    public void laterSightingUpdatesAddressAndKeepsNewestBlob() {
        CarrierSelector sel = new CarrierSelector();
        sel.beginPeriod();
        sel.observe("A", "aa:aa", "", 10, 0);
        sel.observe("A", "cc:cc", "Phone A", 5, 1);
        DiscoveredCarrier a = sel.next(2);
        assertEquals("cc:cc", a.deviceAddress());
        assertEquals("Phone A", a.deviceName());
        assertEquals(10, a.recencyBlobTimestamp());
        assertEquals(0, a.firstSeenAtMillis());
        assertEquals(1, a.lastSeenAtMillis());
    }

    @Test
    public void carriersWithoutAddressAreNotCandidates() {
        CarrierSelector sel = new CarrierSelector();
        sel.beginPeriod();
        sel.observe("A", "", "A", 0, 0);
        assertNull(sel.next(1));
        sel.observe("A", "aa:aa", "A", 0, 2); // a later answer carries the address
        assertEquals("aa:aa", sel.next(3).deviceAddress());
    }

    @Test
    public void failedVisitStillRotatesToTheOtherCarrier() {
        CarrierSelector sel = new CarrierSelector();
        sel.beginPeriod();
        sel.observe("A", "aa:aa", "A", 0, 0);
        sel.observe("B", "bb:bb", "B", 0, 1);
        sel.recordVisit(sel.next(2).carrierId(), 3, false);
        sel.beginPeriod();
        sel.observe("A", "aa:aa", "A", 0, 10);
        sel.observe("B", "bb:bb", "B", 0, 11);
        assertEquals("B", sel.next(12).carrierId());
    }
}
