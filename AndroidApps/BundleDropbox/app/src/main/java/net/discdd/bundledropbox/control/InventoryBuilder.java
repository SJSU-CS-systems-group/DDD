package net.discdd.bundledropbox.control;

import net.discdd.bundledropbox.store.BundleRecord;
import net.discdd.bundledropbox.store.Direction;
import net.discdd.bundledropbox.store.FileDeliveryLog;
import net.discdd.bundledropbox.store.RelayStore;
import net.discdd.bundledropbox.store.StoreStats;
import net.discdd.dropbox.control.BundleJarInfo;
import net.discdd.dropbox.control.DropboxControl;
import net.discdd.dropbox.control.DropboxControlException;
import net.discdd.dropbox.control.InventoryReport;
import net.discdd.dropbox.control.InventoryCompleteness;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds the dropbox's {@link InventoryReport}: a bounded page of relayed bundles it holds, how often each
 * pickup was served to clients, and the outcomes of plans the server has not seen yet (Modules/Control Contract).
 *
 * <p>Sources:
 * <ul>
 *   <li>{@link RelayStore#list} for both directions (committed bundles only). The dropbox's own control bundles
 *       never enter the relay store, so they are never reported.</li>
 *   <li>{@link FileDeliveryLog} served events, aggregated per DOWNSTREAM id into {@code servedCount} and
 *       {@code lastServedAt}. They are advisory: the authoritative delivery evidence is the client's own ack,
 *       which reaches the server inside that client's next upstream bundle (Research/R6 §3.2).</li>
 *   <li>{@code clientKeyB64} for every bundle: the store's {@link BundleRecord#clientTag()}, which is the
 *       contract's {@link BundleJarInfo} fingerprint of the jar's plaintext {@code clientBase.pub}, computed once
 *       when the bundle is committed or indexed. The server records the same fingerprint per client, which lets it
 *       map an upstream id it never received to a client and decide SUPERSEDED.</li>
 *   <li>{@link ControlStateStore}: next {@code reportSeq}, {@code lastAppliedPlanSeq}, pending plan results.</li>
 * </ul>
 *
 * <p>Bounds: the codec rejects lists above {@link DropboxControl#MAX_ENTRIES_PER_LIST} and messages above
 * {@link DropboxControl#MAX_MESSAGE_BYTES}. Records are sorted by encrypted ID and paged from the persisted
 * cursor; a page stays in place until a matching plan is applied. The wire message explicitly marks a report
 * complete only if it actually contains every held ID in both directions.
 *
 * <p>Thread-safe ({@code synchronized}). Pure Java, JVM-tested.
 */
public final class InventoryBuilder {
    private static final Logger logger = Logger.getLogger(InventoryBuilder.class.getName());

    /** A built report plus the fingerprint used to skip queuing identical reports. */
    public static final class Draft {
        private final InventoryReport report;
        private final byte[] encoded;
        private final String fingerprint;
        private final String lastUpstreamId;
        private final String lastDownstreamId;
        private final boolean complete;

        Draft(InventoryReport report, byte[] encoded, String fingerprint, String lastUpstreamId,
              String lastDownstreamId, boolean complete) {
            this.report = report;
            this.encoded = encoded;
            this.fingerprint = fingerprint;
            this.lastUpstreamId = lastUpstreamId;
            this.lastDownstreamId = lastDownstreamId;
            this.complete = complete;
        }

        /** @return the report */
        public InventoryReport report() { return report; }

        /** @return the report's codec bytes (the ADU content) */
        public byte[] encoded() { return encoded.clone(); }

        /** @return a digest of the inventory itself (see {@link InventoryBuilder#fingerprint}) */
        public String fingerprint() { return fingerprint; }

        public String lastUpstreamId() { return lastUpstreamId; }

        public String lastDownstreamId() { return lastDownstreamId; }

        public boolean complete() { return complete; }
    }

    private final RelayStore store;
    private final Supplier<List<FileDeliveryLog.Served>> servedEvents;

    /**
     * @param store        the relay store
     * @param servedEvents all DOWNSTREAM "served to a client" events ({@code FileDeliveryLog::readAll})
     */
    public InventoryBuilder(RelayStore store, Supplier<List<FileDeliveryLog.Served>> servedEvents) {
        this.store = Objects.requireNonNull(store, "store");
        this.servedEvents = Objects.requireNonNull(servedEvents, "servedEvents");
    }

    /**
     * Builds the next report ({@code reportSeq = state.lastReportSeq() + 1}).
     *
     * @param state     current control state
     * @param nowMillis wall clock, written as {@code createdAt}
     * @return the report, its encoding and its content fingerprint
     * @throws DropboxControlException only if even an empty report cannot be encoded (a bug)
     */
    public synchronized Draft build(ControlStateStore.Snapshot state, long nowMillis) throws DropboxControlException {
        List<BundleRecord> allUp = new ArrayList<>(store.list(Direction.UPSTREAM));
        List<BundleRecord> allDown = new ArrayList<>(store.list(Direction.DOWNSTREAM));
        allUp.sort(Comparator.comparing(BundleRecord::bundleId));
        allDown.sort(Comparator.comparing(BundleRecord::bundleId));
        List<BundleRecord> upRecords = page(allUp, state.upstreamCursor(), state.pendingUpstreamEnd(),
                                            state.hasPendingPage());
        List<BundleRecord> downRecords = page(allDown, state.downstreamCursor(), state.pendingDownstreamEnd(),
                                              state.hasPendingPage());
        StoreStats stats = store.stats();

        Map<String, long[]> served = aggregateServed(); // id -> {count, last}
        List<InventoryReport.UpstreamEntry> up = new ArrayList<>(upRecords.size());
        for (BundleRecord r : upRecords) {
            up.add(new InventoryReport.UpstreamEntry(r.bundleId(), r.sizeBytes(), r.clientTag()));
        }
        List<InventoryReport.DownstreamEntry> down = new ArrayList<>(downRecords.size());
        for (BundleRecord r : downRecords) {
            long[] s = served.get(r.bundleId());
            down.add(new InventoryReport.DownstreamEntry(r.bundleId(), r.sizeBytes(),
                                                         r.clientTag(),
                                                         s == null ? 0 : s[0], s == null ? null : s[1]));
        }
        List<InventoryReport.PlanResult> results = new ArrayList<>(state.pendingResults());

        long reportSeq = state.lastReportSeq() + 1;
        long used = stats.upstreamBytes() + stats.downstreamBytes(); // relayed bundles only (contract)
        int upLimit = Math.min(up.size(), DropboxControl.MAX_ENTRIES_PER_LIST);
        int downLimit = Math.min(down.size(), DropboxControl.MAX_ENTRIES_PER_LIST);
        int resultLimit = Math.min(results.size(), DropboxControl.MAX_PLAN_RESULTS);
        while (true) {
            InventoryReport report = new InventoryReport(reportSeq, state.lastAppliedPlanSeq(),
                                                         stats.capacityBytes(), used, nowMillis,
                                                         up.subList(0, upLimit), down.subList(0, downLimit),
                                                         results.subList(0, resultLimit));
            try {
                boolean complete = upLimit == allUp.size() && downLimit == allDown.size();
                byte[] encoded = InventoryCompleteness.encode(report, complete);
                if (upLimit < up.size() || downLimit < down.size() || resultLimit < results.size()) {
                    logger.log(Level.WARNING, "Inventory trimmed to fit the contract bounds: " + upLimit + "/" +
                            up.size() + " up, " + downLimit + "/" + down.size() + " down, " + resultLimit + "/" +
                            results.size() + " plan results");
                }
                String upEnd = upLimit == 0 ? null : up.get(upLimit - 1).getBundleId();
                String downEnd = downLimit == 0 ? null : down.get(downLimit - 1).getBundleId();
                return new Draft(report, encoded, fingerprint(report, complete), upEnd, downEnd, complete);
            } catch (DropboxControlException e) {
                if (e.getKind() != DropboxControlException.Kind.TOO_LARGE) throw e;
                if (upLimit == 0 && downLimit == 0 && resultLimit <= 1) throw e;
                // Halve the largest list and retry. Old pending outcomes go first: only an exact
                // report acknowledgement proves they were seen, and newer outcomes must not starve them.
                if (upLimit >= downLimit && upLimit > 0) upLimit /= 2;
                else if (downLimit > 0) downLimit /= 2;
                else resultLimit = Math.max(1, resultLimit / 2);
            }
        }
    }

    private static List<BundleRecord> page(List<BundleRecord> sorted, String cursor, String pendingEnd,
                                           boolean pending) {
        if (sorted.isEmpty()) return List.of();
        if (!pending && cursor == null && sorted.size() <= DropboxControl.MAX_ENTRIES_PER_LIST) return sorted;
        List<BundleRecord> selected = new ArrayList<>();
        boolean wrapped = pending && cursor != null && pendingEnd != null && pendingEnd.compareTo(cursor) <= 0;
        for (BundleRecord record : sorted) {
            String id = record.bundleId();
            if (pending) {
                if (pendingEnd == null || (wrapped ? id.compareTo(pendingEnd) > 0 :
                        (cursor != null && id.compareTo(cursor) <= 0) || id.compareTo(pendingEnd) > 0)) continue;
            } else if (cursor != null && id.compareTo(cursor) <= 0) {
                continue;
            }
            selected.add(record);
            if (selected.size() == DropboxControl.MAX_ENTRIES_PER_LIST) break;
        }
        if (!pending && selected.isEmpty()) {
            return new ArrayList<>(sorted.subList(0, Math.min(sorted.size(), DropboxControl.MAX_ENTRIES_PER_LIST)));
        }
        return selected;
    }

    /**
     * Digest of what the dropbox holds: capacity, usage and both bundle lists. Two reports with the same
     * fingerprint give the server's planner the same input.
     *
     * <p>{@code reportSeq}, {@code createdAt}, {@code lastAppliedPlanSeq} and {@code planResults} are left out on
     * purpose. The server answers every accepted report with a plan, even an empty one (server
     * {@code DropboxControlHandler}; 06 Storage Management Design § Server). If applying that plan changed the fingerprint, the dropbox would send a new
     * report, which gets another plan, and so on: one report and one plan per carrier round trip, forever.
     * Outcomes that matter (a plan that deleted or skipped something) still trigger a report through
     * {@link ControlStateStore.Snapshot#hasUnreportedResults()}, and a deletion changes the bundle lists anyway.
     */
    static String fingerprint(InventoryReport r) throws DropboxControlException {
        return fingerprint(r, false);
    }

    private static String fingerprint(InventoryReport r, boolean complete) throws DropboxControlException {
        InventoryReport normalized = new InventoryReport(1, 0, r.getCapacityBytes(), r.getUsedBytes(), null,
                                                         r.getUpstream(), r.getDownstream(), null);
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    sha.digest(InventoryCompleteness.encode(normalized, complete)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private Map<String, long[]> aggregateServed() {
        Map<String, long[]> out = new HashMap<>();
        List<FileDeliveryLog.Served> events;
        try {
            events = servedEvents.get();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not read the delivery log; reporting servedCount 0", e);
            return out;
        }
        for (FileDeliveryLog.Served s : events) {
            long[] agg = out.computeIfAbsent(s.bundleId(), k -> new long[]{0, 0});
            agg[0]++;
            agg[1] = Math.max(agg[1], s.servedAtMillis());
        }
        return out;
    }
}
