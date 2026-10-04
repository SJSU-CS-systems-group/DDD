package net.discdd.server.dropbox;

import net.discdd.dropbox.control.DropboxControl.Reason;
import net.discdd.dropbox.control.InventoryReport;
import net.discdd.dropbox.control.RetentionPlan.Deletion;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Decides which bundles a dropbox may delete, from one inventory report and the server's facts. Pure Java: no
 * Spring, no database, no clock; the caller passes every fact through {@link Facts} and the current time, so the rules
 * are unit-testable in isolation. The rules follow "06 Storage Management Design" § Server and Research R8 §7.3.
 * <p>
 * <b>Upstream</b> (client to server bundles the dropbox relays), in order:
 * <ol>
 *   <li>the server has received this exact ID → delete, {@code RECEIVED_BY_SERVER};</li>
 *   <li>the entry's client key maps to a known client, its ID decrypts with that client's key to counter {@code m},
 *       and the server has received an upstream bundle with counter ≥ {@code m} from that client → delete,
 *       {@code SUPERSEDED}. Safe because a client deletes its ADUs only after the server ACKs a bundle carrying them,
 *       so a newer bundle re-carries everything still unacknowledged;</li>
 *   <li>otherwise keep. If the server could not map the entry to a client and counter at all ("unknown"), it is
 *       kept only until {@link #unknownTtl} after its first sighting, then deleted with {@code EXPIRED}.</li>
 * </ol>
 * <b>Downstream</b> (server to client bundles the dropbox holds for a client), in order:
 * <ol>
 *   <li>the server issued this ID for {@code (client, counter)} and the client has acknowledged a counter ≥ that →
 *       delete, {@code CLIENT_ACKED} (the client now rejects it anyway);</li>
 *   <li>the same report also holds a downstream bundle the server issued for the same client with a higher counter
 *       → delete, {@code SUPERSEDED} (the newer one re-carries every unacknowledged ADU). Supersession is judged only
 *       against bundles the dropbox itself holds, never against newer bundles the server issued elsewhere: the
 *       dropbox may never receive those;</li>
 *   <li>otherwise keep. A bundle the server never issued (another server, or a wiped database) is "unknown" and
 *       expires like an unknown upstream entry.</li>
 * </ol>
 * A bundle is never deleted because it is missing from a report: only IDs present in the report can appear in the
 * decision. Mapped entries that no rule deletes (a pending upstream newer than anything received, the newest
 * unacknowledged downstream for a client) are kept without a deadline, because the server knows they are live.
 * The planner is deterministic, so re-planning an unchanged report against unchanged facts gives an equal
 * {@link Decision}.
 */
public final class DropboxRetentionPlanner {
    public enum Direction { UPSTREAM, DOWNSTREAM }

    /** What the server knows. Implementations should be cheap per call (bulk-load before planning). */
    public interface Facts {
        /** whether the server has received this exact upstream bundle ID */
        boolean wasReceived(String upstreamBundleId);

        /** the client ID whose base key has this fingerprint, or null if unknown */
        String clientIdForKey(String clientKeyFingerprint);

        /** the upstream counter of this ID if it decrypts as a bundle of {@code clientId}, otherwise empty */
        OptionalLong upstreamCounter(String upstreamBundleId, String clientId);

        /** the highest upstream counter received from the client, or 0 if none */
        long maxReceivedCounter(String clientId);

        /** the client and counter this server issued the downstream ID for, or null if it never issued it */
        IssuedBundle issuedDownstream(String downstreamBundleId);

        /** the highest downstream counter the client has acknowledged, or 0 if none */
        long ackedCounter(String clientId);

        /** epoch ms when the server first saw this ID in this dropbox's inventory, or null if never */
        Long firstSeenMs(Direction direction, String bundleId);
    }

    public record IssuedBundle(String clientId, long counter) {}

    /** The bundles to delete, in report order, plus how many entries were kept. */
    public record Decision(List<Deletion> deleteUpstream, List<Deletion> deleteDownstream, int keptUpstream,
                           int keptDownstream) {
        public Decision {
            deleteUpstream = List.copyOf(deleteUpstream);
            deleteDownstream = List.copyOf(deleteDownstream);
        }

        /** true if both decisions delete the same IDs for the same reasons (the kept counts follow from that) */
        public boolean sameDeletions(Decision other) {
            return other != null && deleteUpstream.equals(other.deleteUpstream) &&
                    deleteDownstream.equals(other.deleteDownstream);
        }
    }

    private final Duration unknownTtl;

    /**
     * @param unknownTtl how long an unknown bundle is kept after its first sighting; zero or negative never expires
     */
    public DropboxRetentionPlanner(Duration unknownTtl) {
        this.unknownTtl = Objects.requireNonNull(unknownTtl, "unknownTtl");
    }

    public Duration getUnknownTtl() {
        return unknownTtl;
    }

    public Decision plan(InventoryReport report, Facts facts, long nowMs) {
        var deleteUpstream = new ArrayList<Deletion>();
        var seenUpstream = new LinkedHashSet<String>();
        int keptUpstream = 0;
        for (var entry : report.getUpstream()) {
            if (!seenUpstream.add(entry.getBundleId())) continue; // a duplicate entry is decided once
            Reason reason = upstreamReason(entry, facts, nowMs);
            if (reason == null) keptUpstream++;
            else deleteUpstream.add(new Deletion(entry.getBundleId(), reason));
        }

        // the highest counter the dropbox holds per client, among downstream bundles this server issued
        var issued = new HashMap<String, IssuedBundle>();
        var newestHeld = new HashMap<String, Long>();
        for (var entry : report.getDownstream()) {
            var bundle = facts.issuedDownstream(entry.getBundleId());
            if (bundle == null) continue;
            issued.put(entry.getBundleId(), bundle);
            newestHeld.merge(bundle.clientId(), bundle.counter(), Math::max);
        }
        var deleteDownstream = new ArrayList<Deletion>();
        var seenDownstream = new LinkedHashSet<String>();
        int keptDownstream = 0;
        for (var entry : report.getDownstream()) {
            if (!seenDownstream.add(entry.getBundleId())) continue;
            Reason reason = downstreamReason(entry.getBundleId(), issued, newestHeld, facts, nowMs);
            if (reason == null) keptDownstream++;
            else deleteDownstream.add(new Deletion(entry.getBundleId(), reason));
        }
        return new Decision(deleteUpstream, deleteDownstream, keptUpstream, keptDownstream);
    }

    private Reason upstreamReason(InventoryReport.UpstreamEntry entry, Facts facts, long nowMs) {
        String id = entry.getBundleId();
        if (facts.wasReceived(id)) return Reason.RECEIVED_BY_SERVER;
        String key = entry.getClientKeyB64();
        String clientId = key == null ? null : facts.clientIdForKey(key);
        if (clientId != null) {
            OptionalLong counter = facts.upstreamCounter(id, clientId);
            if (counter.isPresent()) {
                // mapped: either superseded, or a live pending relay that is kept without a deadline
                return counter.getAsLong() <= facts.maxReceivedCounter(clientId) ? Reason.SUPERSEDED : null;
            }
        }
        return expired(Direction.UPSTREAM, id, facts, nowMs) ? Reason.EXPIRED : null;
    }

    private Reason downstreamReason(String id,
                                    Map<String, IssuedBundle> issued,
                                    Map<String, Long> newestHeld,
                                    Facts facts,
                                    long nowMs) {
        var bundle = issued.get(id);
        if (bundle == null) return expired(Direction.DOWNSTREAM, id, facts, nowMs) ? Reason.EXPIRED : null;
        if (bundle.counter() <= facts.ackedCounter(bundle.clientId())) return Reason.CLIENT_ACKED;
        if (bundle.counter() < newestHeld.get(bundle.clientId())) return Reason.SUPERSEDED;
        return null;
    }

    private boolean expired(Direction direction, String id, Facts facts, long nowMs) {
        if (unknownTtl.isZero() || unknownTtl.isNegative()) return false;
        Long firstSeen = facts.firstSeenMs(direction, id);
        return firstSeen != null && nowMs - firstSeen >= unknownTtl.toMillis();
    }
}
