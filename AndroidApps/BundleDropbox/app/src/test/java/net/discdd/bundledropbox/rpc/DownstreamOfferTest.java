package net.discdd.bundledropbox.rpc;

import net.discdd.bundledropbox.store.BundleRecord;
import net.discdd.bundledropbox.store.Direction;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class DownstreamOfferTest {
    private static BundleRecord rec(String id, String tag, long builtAt) {
        return new BundleRecord(id, Direction.DOWNSTREAM, 10, builtAt, builtAt, tag, builtAt);
    }

    @Test
    public void includesOlderAndNewerVersionsEvenWhenNewerArrivedFirst() {
        List<BundleRecord> held = List.of(rec("x11", "X", 11_000),
                                          rec("x5", "X", 5_000),
                                          rec("y1", "Y", 1_000));
        assertEquals(List.of("x11", "x5", "y1"), DownstreamOffer.snapshot(held));
    }
}
