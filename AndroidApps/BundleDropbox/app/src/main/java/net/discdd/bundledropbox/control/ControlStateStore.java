package net.discdd.bundledropbox.control;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import net.discdd.dropbox.control.DropboxControl;
import net.discdd.dropbox.control.InventoryReport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The dropbox side of the control channel's bookkeeping, persisted as one JSON file ({@value #FILE_NAME}) in the
 * control endpoint's directory, so it lives and dies with the endpoint identity (a server-key change wipes both).
 *
 * <p>What it remembers and why:
 * <ul>
 *   <li>{@code lastReportSeq}: the {@code reportSeq} of the newest inventory queued. The server ignores a report
 *       that is not newer than the last one it stored, so the sequence must survive restarts.</li>
 *   <li>{@code lastAppliedPlanSeq}: plans are applied only if {@code planSeq > lastAppliedPlanSeq} (contract rule),
 *       which makes replays and re-deliveries harmless.</li>
 *   <li>{@code pendingResults}: outcomes of applied plans that the server has not seen yet. Each one is sent in
 *       every inventory until a later plan is based on a report that carried it, which proves the server read it
 *       (bounded by {@link DropboxControl#MAX_PLAN_RESULTS}).</li>
 *   <li>{@code lastReportFingerprint}: a digest of the last queued report's inventory (see
 *       {@code InventoryBuilder.fingerprint}). It lets the manager skip queuing an unchanged report, so an
 *       unchanged dropbox keeps re-offering the same bundle instead of burning a new bundle id per visit
 *       (Research/R7 §7).</li>
 *   <li>Totals of bundles deleted on server orders, per direction, for the Storage tab.</li>
 * </ul>
 *
 * <p>Writes are atomic (temp file, then rename). An unreadable file starts from zero, which is safe: a too-low
 * {@code lastAppliedPlanSeq} only re-applies monotonic facts, and a too-low {@code reportSeq} is corrected the next
 * time the server's plan names a higher {@code basedOnReportSeq} ({@link #recordPlanApplied}).
 *
 * <p>Thread-safe (all methods synchronized). Pure Java, JVM-tested.
 */
public final class ControlStateStore {
    private static final Logger logger = Logger.getLogger(ControlStateStore.class.getName());

    /** File name inside the control state directory. */
    public static final String FILE_NAME = "control-state.json";

    private static final Gson GSON = new Gson();

    private final Path file;
    private State state; // guarded by this

    /**
     * Loads the state from {@code file}, or starts empty if it is missing or unreadable.
     *
     * @param file the state file (its parent directory is created on the first save)
     */
    public ControlStateStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        this.state = load(file);
    }

    /** @return an immutable copy of the current state */
    public synchronized Snapshot snapshot() {
        return new Snapshot(state);
    }

    /**
     * Records that an inventory with {@code reportSeq} was queued in the endpoint's send store.
     *
     * @param reportSeq   the report's sequence number (greater than {@link Snapshot#lastReportSeq()})
     * @param fingerprint content fingerprint of the report (see {@code InventoryBuilder.fingerprint})
     * @param nowMillis   wall clock
     */
    public synchronized void recordReportQueued(long reportSeq, String fingerprint, long nowMillis) throws IOException {
        State next = state.copy();
        next.lastReportSeq = Math.max(next.lastReportSeq, reportSeq);
        next.lastReportFingerprint = fingerprint;
        next.lastReportAtMillis = nowMillis;
        for (PendingResult r : next.pendingResults) {
            if (r.firstReportSeq == 0) r.firstReportSeq = reportSeq;
        }
        save(next);
    }

    /**
     * Records an applied plan: advances {@code lastAppliedPlanSeq}, queues its result for the next inventory,
     * forgets results the server has demonstrably seen, and adds the deletion totals.
     *
     * @param planSeq          the applied plan's sequence number
     * @param basedOnReportSeq the report the server computed the plan from
     * @param result           what the dropbox did
     * @param deletedUp        UPSTREAM bundles deleted by this plan
     * @param deletedUpBytes   their bytes
     * @param deletedDown      DOWNSTREAM bundles deleted by this plan
     * @param deletedDownBytes their bytes
     * @param nowMillis        wall clock
     */
    public synchronized void recordPlanApplied(long planSeq, long basedOnReportSeq, InventoryReport.PlanResult result,
                                               int deletedUp, long deletedUpBytes, int deletedDown,
                                               long deletedDownBytes, long nowMillis) throws IOException {
        State next = state.copy();
        next.lastAppliedPlanSeq = Math.max(next.lastAppliedPlanSeq, planSeq);
        next.lastPlanAtMillis = nowMillis;
        next.lastPlanBasedOnReportSeq = basedOnReportSeq;
        // A report numbered above ours can only exist if our counter was lost (e.g. the state file was damaged):
        // continue above it so the server does not ignore every later report as stale.
        next.lastReportSeq = Math.max(next.lastReportSeq, basedOnReportSeq);
        // The server read every report up to basedOnReportSeq, so results first sent in one of those are known.
        next.pendingResults.removeIf(r -> r.firstReportSeq != 0 && r.firstReportSeq <= basedOnReportSeq);
        next.pendingResults.add(PendingResult.of(result));
        while (next.pendingResults.size() > DropboxControl.MAX_PLAN_RESULTS) next.pendingResults.remove(0);
        next.deletedUpstreamCount += deletedUp;
        next.deletedUpstreamBytes += deletedUpBytes;
        next.deletedDownstreamCount += deletedDown;
        next.deletedDownstreamBytes += deletedDownBytes;
        save(next);
    }

    private void save(State next) throws IOException {
        byte[] json = GSON.toJson(next).getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, json);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        state = next; // only after the file is durable, so memory never runs ahead of disk
    }

    private static State load(Path file) {
        try {
            State s = GSON.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), State.class);
            if (s == null) return new State();
            if (s.pendingResults == null) s.pendingResults = new ArrayList<>();
            s.pendingResults.removeIf(r -> r == null || r.planSeq <= 0);
            return s;
        } catch (NoSuchFileException e) {
            return new State();
        } catch (IOException | JsonParseException | IllegalStateException e) {
            logger.log(Level.WARNING, "Unreadable control state " + file + "; starting from zero", e);
            return new State();
        }
    }

    /** Persisted form (Gson). Mutable; only ever mutated on a copy inside the store's lock. */
    private static final class State {
        long lastReportSeq;
        long lastReportAtMillis;
        String lastReportFingerprint;
        long lastAppliedPlanSeq;
        long lastPlanAtMillis;
        long lastPlanBasedOnReportSeq;
        long deletedUpstreamCount;
        long deletedUpstreamBytes;
        long deletedDownstreamCount;
        long deletedDownstreamBytes;
        List<PendingResult> pendingResults = new ArrayList<>();

        State copy() {
            State c = new State();
            c.lastReportSeq = lastReportSeq;
            c.lastReportAtMillis = lastReportAtMillis;
            c.lastReportFingerprint = lastReportFingerprint;
            c.lastAppliedPlanSeq = lastAppliedPlanSeq;
            c.lastPlanAtMillis = lastPlanAtMillis;
            c.lastPlanBasedOnReportSeq = lastPlanBasedOnReportSeq;
            c.deletedUpstreamCount = deletedUpstreamCount;
            c.deletedUpstreamBytes = deletedUpstreamBytes;
            c.deletedDownstreamCount = deletedDownstreamCount;
            c.deletedDownstreamBytes = deletedDownstreamBytes;
            c.pendingResults = new ArrayList<>();
            for (PendingResult r : pendingResults) c.pendingResults.add(r.copy());
            return c;
        }
    }

    /** One plan outcome waiting to reach the server (Gson form of {@link InventoryReport.PlanResult}). */
    private static final class PendingResult {
        long planSeq;
        List<String> deleted = new ArrayList<>();
        List<String> skippedIds = new ArrayList<>();
        List<String> skippedReasons = new ArrayList<>();
        /** reportSeq of the first inventory that carried this result; 0 = not reported yet. */
        long firstReportSeq;

        static PendingResult of(InventoryReport.PlanResult r) {
            PendingResult p = new PendingResult();
            p.planSeq = r.getPlanSeq();
            p.deleted.addAll(r.getDeleted());
            for (InventoryReport.SkippedEntry s : r.getSkipped()) {
                p.skippedIds.add(s.getBundleId());
                p.skippedReasons.add(s.getReason() == null ? null : s.getReason().name());
            }
            return p;
        }

        /** @return true if the plan neither deleted nor skipped anything */
        boolean isEmpty() {
            return (deleted == null || deleted.isEmpty()) && (skippedIds == null || skippedIds.isEmpty());
        }

        PendingResult copy() {
            PendingResult p = new PendingResult();
            p.planSeq = planSeq;
            p.deleted = new ArrayList<>(deleted == null ? List.of() : deleted);
            p.skippedIds = new ArrayList<>(skippedIds == null ? List.of() : skippedIds);
            p.skippedReasons = new ArrayList<>(skippedReasons == null ? List.of() : skippedReasons);
            p.firstReportSeq = firstReportSeq;
            return p;
        }

        InventoryReport.PlanResult toResult() {
            List<InventoryReport.SkippedEntry> skipped = new ArrayList<>();
            for (int i = 0; i < skippedIds.size(); i++) {
                String reason = i < skippedReasons.size() ? skippedReasons.get(i) : null;
                skipped.add(new InventoryReport.SkippedEntry(skippedIds.get(i), parseSkip(reason)));
            }
            return new InventoryReport.PlanResult(planSeq, deleted, skipped);
        }

        private static DropboxControl.SkipReason parseSkip(String s) {
            if (s == null) return null;
            try {
                return DropboxControl.SkipReason.valueOf(s);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** Immutable view of the control state. */
    public static final class Snapshot {
        private final long lastReportSeq;
        private final long lastReportAtMillis;
        private final String lastReportFingerprint;
        private final long lastAppliedPlanSeq;
        private final long lastPlanAtMillis;
        private final long lastPlanBasedOnReportSeq;
        private final long deletedUpstreamCount;
        private final long deletedUpstreamBytes;
        private final long deletedDownstreamCount;
        private final long deletedDownstreamBytes;
        private final List<InventoryReport.PlanResult> pendingResults;
        private final boolean hasUnreportedResults;

        private Snapshot(State s) {
            lastReportSeq = s.lastReportSeq;
            lastReportAtMillis = s.lastReportAtMillis;
            lastReportFingerprint = s.lastReportFingerprint;
            lastAppliedPlanSeq = s.lastAppliedPlanSeq;
            lastPlanAtMillis = s.lastPlanAtMillis;
            lastPlanBasedOnReportSeq = s.lastPlanBasedOnReportSeq;
            deletedUpstreamCount = s.deletedUpstreamCount;
            deletedUpstreamBytes = s.deletedUpstreamBytes;
            deletedDownstreamCount = s.deletedDownstreamCount;
            deletedDownstreamBytes = s.deletedDownstreamBytes;
            List<InventoryReport.PlanResult> results = new ArrayList<>();
            for (PendingResult r : s.pendingResults) results.add(r.toResult());
            pendingResults = Collections.unmodifiableList(results);
            boolean unreported = false;
            for (PendingResult r : s.pendingResults) {
                if (r.firstReportSeq == 0 && !r.isEmpty()) unreported = true;
            }
            hasUnreportedResults = unreported;
        }

        /** @return reportSeq of the newest queued inventory, 0 if none yet */
        public long lastReportSeq() { return lastReportSeq; }

        /** @return wall-clock time the newest inventory was queued, 0 if none yet */
        public long lastReportAtMillis() { return lastReportAtMillis; }

        /** @return content fingerprint of the newest queued inventory, or {@code null} */
        public String lastReportFingerprint() { return lastReportFingerprint; }

        /** @return planSeq of the newest applied plan, 0 until the first plan is applied */
        public long lastAppliedPlanSeq() { return lastAppliedPlanSeq; }

        /** @return wall-clock time the newest plan was applied, 0 if none yet */
        public long lastPlanAtMillis() { return lastPlanAtMillis; }

        /** @return the report the newest applied plan was based on, 0 if none yet */
        public long lastPlanBasedOnReportSeq() { return lastPlanBasedOnReportSeq; }

        /** @return UPSTREAM bundles deleted on server orders since this endpoint was created */
        public long deletedUpstreamCount() { return deletedUpstreamCount; }

        /** @return their total size in bytes */
        public long deletedUpstreamBytes() { return deletedUpstreamBytes; }

        /** @return DOWNSTREAM bundles deleted on server orders since this endpoint was created */
        public long deletedDownstreamCount() { return deletedDownstreamCount; }

        /** @return their total size in bytes */
        public long deletedDownstreamBytes() { return deletedDownstreamBytes; }

        /** @return plan outcomes the server has not seen yet, oldest first */
        public List<InventoryReport.PlanResult> pendingResults() { return pendingResults; }

        /**
         * @return true if a plan deleted or skipped something and no queued report has carried that outcome yet.
         *         Outcomes of plans that changed nothing do not count: the server answers every accepted report
         *         with a plan, often an empty one, so reporting empty outcomes would make each plan trigger a
         *         report and each report a plan, forever.
         */
        public boolean hasUnreportedResults() { return hasUnreportedResults; }
    }
}
