package net.discdd.bundledropbox.control;

import net.discdd.bundledropbox.store.BundleRecord;
import net.discdd.bundledropbox.store.Direction;
import net.discdd.bundledropbox.store.FileDeliveryLog;
import net.discdd.bundledropbox.store.RelayStore;
import net.discdd.bundledropbox.store.StoreStats;
import net.discdd.dropbox.control.BundleJarInfo;
import net.discdd.dropbox.control.DropboxControl;
import net.discdd.dropbox.control.DropboxControlCodec;
import net.discdd.dropbox.control.DropboxControlException;
import net.discdd.dropbox.control.InventoryReport;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds the dropbox's {@link InventoryReport}: a full snapshot of the relayed bundles it holds, how often each
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
 * {@link DropboxControl#MAX_MESSAGE_BYTES}. A dropbox holding more is reported partially, oldest bundles first:
 * they are the likeliest to be released, and the server never deletes because of absence, so omitted bundles
 * are simply reported once space frees up.
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

        Draft(InventoryReport report, byte[] encoded, String fingerprint) {
            this.report = report;
            this.encoded = encoded;
            this.fingerprint = fingerprint;
        }

        /** @return the report */
        public InventoryReport report() { return report; }

        /** @return the report's codec bytes (the ADU content) */
        public byte[] encoded() { return encoded.clone(); }

        /** @return a digest of the inventory itself (see {@link InventoryBuilder#fingerprint}) */
        public String fingerprint() { return fingerprint; }
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
        List<BundleRecord> upRecords = store.list(Direction.UPSTREAM);
        List<BundleRecord> downRecords = store.list(Direction.DOWNSTREAM);
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
                                                         results.subList(results.size() - resultLimit,
                                                                         results.size()));
            try {
                byte[] encoded = DropboxControlCodec.encode(report);
                if (upLimit < up.size() || downLimit < down.size() || resultLimit < results.size()) {
                    logger.log(Level.WARNING, "Inventory trimmed to fit the contract bounds: " + upLimit + "/" +
                            up.size() + " up, " + downLimit + "/" + down.size() + " down, " + resultLimit + "/" +
                            results.size() + " plan results");
                }
                return new Draft(report, encoded, fingerprint(report));
            } catch (DropboxControlException e) {
                if (e.getKind() != DropboxControlException.Kind.TOO_LARGE) throw e;
                if (upLimit == 0 && downLimit == 0 && resultLimit <= 1) throw e;
                // Halve the largest list and retry; keep the newest plan results (the oldest are the likeliest
                // to have been seen by the server already).
                if (upLimit >= downLimit && upLimit > 0) upLimit /= 2;
                else if (downLimit > 0) downLimit /= 2;
                else resultLimit = Math.max(1, resultLimit / 2);
            }
        }
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
        InventoryReport normalized = new InventoryReport(1, 0, r.getCapacityBytes(), r.getUsedBytes(), null,
                                                         r.getUpstream(), r.getDownstream(), null);
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    sha.digest(DropboxControlCodec.encode(normalized)));
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
