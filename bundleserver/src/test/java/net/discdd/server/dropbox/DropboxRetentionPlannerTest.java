package net.discdd.server.dropbox;

import net.discdd.dropbox.control.DropboxControl.Reason;
import net.discdd.dropbox.control.InventoryReport;
import net.discdd.dropbox.control.InventoryReport.DownstreamEntry;
import net.discdd.dropbox.control.InventoryReport.UpstreamEntry;
import net.discdd.dropbox.control.RetentionPlan.Deletion;
import net.discdd.server.dropbox.DropboxRetentionPlanner.Decision;
import net.discdd.server.dropbox.DropboxRetentionPlanner.Direction;
import net.discdd.server.dropbox.DropboxRetentionPlanner.IssuedBundle;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rule-by-rule tests of {@link DropboxRetentionPlanner} against in-memory facts. */
class DropboxRetentionPlannerTest {
    private static final long NOW = 1_759_500_000_000L;
    private static final long DAY = Duration.ofDays(1).toMillis();
    private final DropboxRetentionPlanner planner = new DropboxRetentionPlanner(Duration.ofDays(14));
    private final FakeFacts facts = new FakeFacts();

    /** In-memory facts; upstream IDs "decrypt" through an explicit (id, client) → counter table. */
    static final class FakeFacts implements DropboxRetentionPlanner.Facts {
        final Set<String> received = new HashSet<>();
        final Map<String, String> clientByKey = new HashMap<>();
        final Map<String, Long> upstreamCounters = new HashMap<>();
        final Map<String, Long> maxReceived = new HashMap<>();
        final Map<String, IssuedBundle> issued = new HashMap<>();
        final Map<String, Long> acked = new HashMap<>();
        final Map<String, Long> firstSeen = new HashMap<>();

        @Override
        public boolean wasReceived(String id) {return received.contains(id);}

        @Override
        public String clientIdForKey(String key) {return clientByKey.get(key);}

        @Override
        public OptionalLong upstreamCounter(String id, String clientId) {
            Long c = upstreamCounters.get(id + "@" + clientId);
            return c == null ? OptionalLong.empty() : OptionalLong.of(c);
        }

        @Override
        public long maxReceivedCounter(String clientId) {return maxReceived.getOrDefault(clientId, 0L);}

        @Override
        public IssuedBundle issuedDownstream(String id) {return issued.get(id);}

        @Override
        public long ackedCounter(String clientId) {return acked.getOrDefault(clientId, 0L);}

        @Override
        public Long firstSeenMs(Direction direction, String id) {return firstSeen.get(direction + "/" + id);}
    }

    private static InventoryReport report(List<UpstreamEntry> up, List<DownstreamEntry> down) {
        return new InventoryReport(1, 0, 1000, 10, NOW, up, down, List.of());
    }

    private static UpstreamEntry up(String id, String key) {return new UpstreamEntry(id, 10, key);}

    private static DownstreamEntry down(String id) {return new DownstreamEntry(id, 10, null, 0, null);}

    private Decision plan(InventoryReport report) {return planner.plan(report, facts, NOW);}

    @Test
    void upstreamReceivedByServerIsDeleted() {
        facts.received.add("U1");
        var d = plan(report(List.of(up("U1", null), up("U2", null)), List.of()));
        assertEquals(List.of(new Deletion("U1", Reason.RECEIVED_BY_SERVER)), d.deleteUpstream());
        assertEquals(1, d.keptUpstream());
    }

    @Test
    void upstreamSupersededWhenClientHasNewerOrEqualCounter() {
        facts.clientByKey.put("keyC", "C");
        facts.upstreamCounters.put("U3@C", 3L);
        facts.upstreamCounters.put("U5@C", 5L);
        facts.upstreamCounters.put("U6@C", 6L);
        facts.maxReceived.put("C", 5L);
        var d = plan(report(List.of(up("U3", "keyC"), up("U5", "keyC"), up("U6", "keyC")), List.of()));
        assertEquals(List.of(new Deletion("U3", Reason.SUPERSEDED), new Deletion("U5", Reason.SUPERSEDED)),
                     d.deleteUpstream());
        assertEquals(1, d.keptUpstream(), "U6 is newer than anything received: a live pending relay");
    }

    @Test
    void upstreamReceivedWinsOverSuperseded() {
        facts.received.add("U3");
        facts.clientByKey.put("keyC", "C");
        facts.upstreamCounters.put("U3@C", 3L);
        facts.maxReceived.put("C", 9L);
        assertEquals(List.of(new Deletion("U3", Reason.RECEIVED_BY_SERVER)),
                     plan(report(List.of(up("U3", "keyC")), List.of())).deleteUpstream());
    }

    @Test
    void upstreamWithKnownKeyButUndecryptableIdIsUnknown() {
        facts.clientByKey.put("keyC", "C");
        facts.maxReceived.put("C", 9L);
        facts.firstSeen.put("UPSTREAM/Ux", NOW - 15 * DAY);
        assertEquals(List.of(new Deletion("Ux", Reason.EXPIRED)),
                     plan(report(List.of(up("Ux", "keyC")), List.of())).deleteUpstream());
    }

    @Test
    void unknownUpstreamIsKeptUntilTtlThenExpired() {
        facts.firstSeen.put("UPSTREAM/young", NOW - 13 * DAY);
        facts.firstSeen.put("UPSTREAM/old", NOW - 14 * DAY);
        var d = plan(report(List.of(up("young", null), up("old", "unmappedKey"), up("unseen", null)), List.of()));
        assertEquals(List.of(new Deletion("old", Reason.EXPIRED)), d.deleteUpstream());
        assertEquals(2, d.keptUpstream());
    }

    @Test
    void mappedPendingUpstreamNeverExpires() {
        facts.clientByKey.put("keyC", "C");
        facts.upstreamCounters.put("U9@C", 9L);
        facts.maxReceived.put("C", 2L);
        facts.firstSeen.put("UPSTREAM/U9", NOW - 365 * DAY);
        assertTrue(plan(report(List.of(up("U9", "keyC")), List.of())).deleteUpstream().isEmpty());
    }

    @Test
    void downstreamAckedByClientIsDeleted() {
        facts.issued.put("R1", new IssuedBundle("C", 1));
        facts.issued.put("R2", new IssuedBundle("C", 2));
        facts.acked.put("C", 1L);
        var d = plan(report(List.of(), List.of(down("R1"))));
        assertEquals(List.of(new Deletion("R1", Reason.CLIENT_ACKED)), d.deleteDownstream());
        d = plan(report(List.of(), List.of(down("R2"))));
        assertTrue(d.deleteDownstream().isEmpty(), "R2 is not acked yet");
    }

    @Test
    void downstreamSupersededOnlyByNewerBundleTheDropboxHolds() {
        facts.issued.put("R1", new IssuedBundle("C", 1));
        facts.issued.put("R2", new IssuedBundle("C", 2));
        facts.issued.put("Q7", new IssuedBundle("Q", 7));
        var d = plan(report(List.of(), List.of(down("R2"), down("R1"), down("Q7"))));
        assertEquals(List.of(new Deletion("R1", Reason.SUPERSEDED)), d.deleteDownstream());
        assertEquals(2, d.keptDownstream());
    }

    @Test
    void downstreamNotSupersededByNewerBundleTheDropboxDoesNotHold() {
        // the server has issued R5 for C, but the dropbox only holds R1: it may never receive R5
        facts.issued.put("R1", new IssuedBundle("C", 1));
        facts.issued.put("R5", new IssuedBundle("C", 5));
        facts.firstSeen.put("DOWNSTREAM/R1", NOW - 100 * DAY);
        assertTrue(plan(report(List.of(), List.of(down("R1")))).deleteDownstream().isEmpty());
    }

    @Test
    void downstreamAckedWinsOverSuperseded() {
        facts.issued.put("R1", new IssuedBundle("C", 1));
        facts.issued.put("R2", new IssuedBundle("C", 2));
        facts.acked.put("C", 1L);
        var d = plan(report(List.of(), List.of(down("R1"), down("R2"))));
        assertEquals(List.of(new Deletion("R1", Reason.CLIENT_ACKED)), d.deleteDownstream());
    }

    @Test
    void unknownDownstreamIsKeptUntilTtlThenExpired() {
        facts.firstSeen.put("DOWNSTREAM/X1", NOW - 20 * DAY);
        facts.firstSeen.put("DOWNSTREAM/X2", NOW - DAY);
        var d = plan(report(List.of(), List.of(down("X1"), down("X2"))));
        assertEquals(List.of(new Deletion("X1", Reason.EXPIRED)), d.deleteDownstream());
    }

    @Test
    void sameIdInBothDirectionsIsDecidedPerDirection() {
        facts.received.add("ID");
        var d = plan(report(List.of(up("ID", null)), List.of(down("ID"))));
        assertEquals(List.of(new Deletion("ID", Reason.RECEIVED_BY_SERVER)), d.deleteUpstream());
        assertTrue(d.deleteDownstream().isEmpty());
    }

    @Test
    void duplicateEntriesAreDecidedOnce() {
        facts.received.add("U1");
        var d = plan(report(List.of(up("U1", null), up("U1", null)), List.of()));
        assertEquals(1, d.deleteUpstream().size());
    }

    @Test
    void nonPositiveTtlNeverExpires() {
        facts.firstSeen.put("UPSTREAM/old", 0L);
        var noTtl = new DropboxRetentionPlanner(Duration.ZERO);
        assertTrue(noTtl.plan(report(List.of(up("old", null)), List.of()), facts, NOW).deleteUpstream().isEmpty());
    }

    @Test
    void emptyReportGivesEmptyDecision() {
        var d = plan(report(List.of(), List.of()));
        assertTrue(d.deleteUpstream().isEmpty() && d.deleteDownstream().isEmpty());
    }

    @Test
    void replanningIsDeterministic() {
        facts.received.add("U1");
        facts.issued.put("R1", new IssuedBundle("C", 1));
        facts.acked.put("C", 1L);
        var r = report(List.of(up("U1", null), up("U2", null)), List.of(down("R1")));
        var first = plan(r);
        var second = plan(r);
        assertEquals(first, second);
        assertTrue(first.sameDeletions(second));
        facts.received.add("U2");
        assertTrue(!first.sameDeletions(plan(r)), "a new fact changes the decision");
    }
}
