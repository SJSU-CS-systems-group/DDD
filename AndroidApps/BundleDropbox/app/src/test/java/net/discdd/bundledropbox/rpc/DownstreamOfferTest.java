package net.discdd.bundledropbox.rpc;

import net.discdd.bundledropbox.store.BundleRecord;
import net.discdd.bundledropbox.store.Direction;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;

/**
 * {@link DownstreamOffer}: newest bundle per client (Audit M7), cap ranked by last-seen (Audit M9).
 */
public class DownstreamOfferTest {

    private static BundleRecord rec(String id, String tag, long builtAt, long committedAt, long lastSeen) {
        return new BundleRecord(id, Direction.DOWNSTREAM, 10, committedAt, lastSeen, tag, builtAt);
    }

    @Test
    public void offersOnlyTheNewestBundlePerClient() {
        List<BundleRecord> held = List.of(rec("x5", "X", 5_000, 100, 100),
                                          rec("x6", "X", 6_000, 200, 200),
                                          rec("x7", "X", 7_000, 300, 300),
                                          rec("y1", "Y", 1_000, 150, 150));
        assertEquals(Set.of("x7", "y1"), Set.copyOf(DownstreamOffer.snapshot(held, 10)));
        assertEquals(Set.of("x7", "y1"), DownstreamOffer.newestPerClient(held));
    }

    @Test
    public void newestIsTheServerBuildTimeNotTheArrivalOrder() {
        // Version 6 arrived first from a fresh carrier, version 5 later from a stale one.
        List<BundleRecord> held = List.of(rec("x6", "X", 6_000, 100, 100), rec("x5", "X", 5_000, 900, 900));
        assertEquals(List.of("x6"), DownstreamOffer.snapshot(held, 10));
    }

    @Test
    public void unknownBuildTimeFallsBackToCommitTimeAndTiesToId() {
        List<BundleRecord> held = List.of(rec("a", "X", BundleRecord.UNKNOWN_TIME, 100, 100),
                                          rec("b", "X", BundleRecord.UNKNOWN_TIME, 200, 200));
        assertEquals(List.of("b"), DownstreamOffer.snapshot(held, 10));
        // Same 2 s jar timestamp: later commit wins.
        List<BundleRecord> tie = List.of(rec("p", "Z", 4_000, 300, 300), rec("q", "Z", 4_000, 100, 100));
        assertEquals(List.of("p"), DownstreamOffer.snapshot(tie, 10));
    }

    @Test
    public void untaggedBundlesAreEachTheirOwnClient() {
        List<BundleRecord> held = List.of(rec("u1", null, 1, 1, 1), rec("u2", null, 2, 2, 2));
        assertEquals(List.of("u1", "u2"), DownstreamOffer.snapshot(held, 10));
    }

    @Test
    public void capKeepsTheClientsMostRecentlySeenByACarrier() {
        // Client X's current bundle was downloaded long ago but a carrier re-offered it just now.
        List<BundleRecord> held = List.of(rec("x1", "X", 1_000, 10, 9_000),
                                          rec("y1", "Y", 2_000, 500, 500),
                                          rec("z1", "Z", 3_000, 600, 600));
        assertEquals(List.of("z1", "x1"), DownstreamOffer.snapshot(held, 2));
    }

    @Test
    public void groupFreshnessIsTheLatestSightingOfAnyVersion() {
        // A stale carrier keeps re-offering version 1 while version 2 is held: the client is still active.
        List<BundleRecord> held = List.of(rec("x1", "X", 1_000, 10, 9_000),
                                          rec("x2", "X", 2_000, 20, 20),
                                          rec("y1", "Y", 1_500, 500, 500));
        assertEquals(List.of("x2"), DownstreamOffer.snapshot(held, 1));
    }
}
