package net.discdd.bundledropbox.rpc;

import net.discdd.bundledropbox.store.BundleRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides which DOWNSTREAM bundles the HOST side offers to clients: only the NEWEST bundle per destination
 * client, and of those the most recently confirmed ones first when a cap applies.
 *
 * <p><b>Why newest per client (Audit M7).</b> A stock transport only ever holds a client's newest bundle,
 * because the server tells it to delete superseded ones at each sync. The dropbox keeps every version it pulled
 * (v1 never deletes on its own). The stock client fills its PSI window in ascending counter order, sorts the
 * matches by window index and downloads only the first, so offering counters 5, 6 and 7 makes it take 5 (stale)
 * and fall one carrier visit behind per version. Server bundles are cumulative (a newer one re-carries every
 * un-acked ADU, Research/R8 §7.3), so offering only the newest loses nothing. Older versions are NOT deleted;
 * they are just not offered (no autonomous deletion; server-managed retention removes them later).
 *
 * <p><b>Grouping without decryption.</b> Bundles are grouped by {@link BundleRecord#clientTag()}, derived from
 * the jar's plaintext {@code clientBase.pub} entry, which the server writes with the same per-client key into
 * every bundle for that client (Research/R8 §5). A bundle without a tag (not a real DDD bundle) forms its own
 * group and is always a candidate. "Newest" is the server's build time ({@link BundleRecord#builtAtMillis()},
 * the jar timestamp): the dropbox may receive version 6 from one carrier before version 5 from a staler one, so
 * its own commit time is not a safe order. If the build time is unknown the commit time stands in; ties (2 s
 * jar resolution) fall back to commit time, then id.
 *
 * <p><b>Cap order (Audit M9).</b> The PSI snapshot is capped ({@code DropboxExchangeService.DEFAULT_MAX_PSI_SNAPSHOT})
 * because PSI cost is linear in its size on both sides. Groups are ranked by the latest
 * {@link BundleRecord#lastSeenAtMillis()} of any member: a carrier re-offering a client's bundle proves that
 * client is still served, so an idle client's current bundle does not age out just because it was first
 * downloaded long ago.
 *
 * <p>Stateless, pure Java; JVM-tested.
 */
final class DownstreamOffer {
    private DownstreamOffer() {}

    /** Newest-first order within one client's group. */
    private static final Comparator<BundleRecord> NEWER_FIRST =
            Comparator.comparingLong(DownstreamOffer::effectiveBuiltAt)
                    .thenComparingLong(BundleRecord::committedAtMillis)
                    .thenComparing(BundleRecord::bundleId)
                    .reversed();

    /**
     * @param downstream all committed DOWNSTREAM records
     * @param max        most ids to offer (&gt; 0)
     * @return the ids to put in one PSI session snapshot: the newest bundle of each client, at most {@code max}
     *         of them, least recently seen first (the transport's snapshot order carries no meaning to the
     *         client, which matches by blinded value); immutable
     */
    static List<String> snapshot(List<BundleRecord> downstream, int max) {
        List<Candidate> candidates = candidates(downstream);
        candidates.sort(Comparator.comparingLong((Candidate c) -> c.groupLastSeen)
                                .thenComparing(c -> c.newest.bundleId()));
        int from = Math.max(0, candidates.size() - max);
        List<String> ids = new ArrayList<>(candidates.size() - from);
        for (Candidate c : candidates.subList(from, candidates.size())) ids.add(c.newest.bundleId());
        return Collections.unmodifiableList(ids);
    }

    /**
     * @param downstream all committed DOWNSTREAM records
     * @return ids of the newest bundle per client, uncapped; the legacy {@code downloadBundle} serves only these,
     *         because the stock client's legacy loop returns the FIRST window id found, i.e. the oldest version
     */
    static Set<String> newestPerClient(List<BundleRecord> downstream) {
        Set<String> ids = new HashSet<>();
        for (Candidate c : candidates(downstream)) ids.add(c.newest.bundleId());
        return ids;
    }

    private static List<Candidate> candidates(List<BundleRecord> downstream) {
        Map<String, Candidate> byClient = new HashMap<>();
        List<Candidate> out = new ArrayList<>();
        for (BundleRecord r : downstream) {
            String tag = r.clientTag();
            if (tag == null) {
                out.add(new Candidate(r));
                continue;
            }
            Candidate c = byClient.get(tag);
            if (c == null) {
                c = new Candidate(r);
                byClient.put(tag, c);
                out.add(c);
            } else {
                c.add(r);
            }
        }
        return out;
    }

    private static long effectiveBuiltAt(BundleRecord r) {
        return r.builtAtMillis() != BundleRecord.UNKNOWN_TIME ? r.builtAtMillis() : r.committedAtMillis();
    }

    /** One client's group: its newest bundle and the group's latest last-seen time. */
    private static final class Candidate {
        BundleRecord newest;
        long groupLastSeen;

        Candidate(BundleRecord first) {
            newest = first;
            groupLastSeen = first.lastSeenAtMillis();
        }

        void add(BundleRecord r) {
            if (NEWER_FIRST.compare(r, newest) < 0) newest = r;
            groupLastSeen = Math.max(groupLastSeen, r.lastSeenAtMillis());
        }
    }
}
