package net.discdd.dropbox.control;

import java.util.List;
import java.util.Objects;

/**
 * Dropbox to server ({@code "type":"inventory"}): a full snapshot of what the dropbox holds, plus the results of
 * the plans it applied since its last report.
 *
 * <p>Snapshot semantics: a newer report ({@link #getReportSeq()} higher) replaces an older one entirely, and the
 * server ignores reports whose sequence is not newer than the last one it stored. The server must never infer a
 * deletion from a bundle's absence in a report (see "06 Storage Management Design", planner rules).
 *
 * <p>Bundle IDs are the encrypted IDs from each jar's {@code bundle.id} entry, which is also the dropbox's file
 * name and the server's {@code uncompressedBundle.getBundleId()} (Research/R8 §4).
 */
public final class InventoryReport extends DropboxControlMessage {
    private long reportSeq;
    private long lastAppliedPlanSeq;
    private long capacityBytes;
    private long usedBytes;
    /** Epoch milliseconds when the dropbox built this report; optional, informational only. */
    private Long createdAt;
    private List<UpstreamEntry> upstream;
    private List<DownstreamEntry> downstream;
    private List<PlanResult> planResults;

    /** For Gson. */
    private InventoryReport() {}

    /**
     * @param reportSeq          monotonic per dropbox endpoint, starting at 1
     * @param lastAppliedPlanSeq highest {@link RetentionPlan#getPlanSeq()} the dropbox has applied, 0 if none
     * @param capacityBytes      the dropbox's storage budget for relayed bundles
     * @param usedBytes          bytes currently used by relayed bundles
     * @param createdAt          epoch ms when the report was built, or null
     * @param upstream           client-to-server bundles held (null means none)
     * @param downstream         server-to-client bundles held (null means none)
     * @param planResults        outcomes of plans applied since the last report (null means none)
     */
    public InventoryReport(long reportSeq, long lastAppliedPlanSeq, long capacityBytes, long usedBytes,
                           Long createdAt, List<UpstreamEntry> upstream, List<DownstreamEntry> downstream,
                           List<PlanResult> planResults) {
        this.reportSeq = reportSeq;
        this.lastAppliedPlanSeq = lastAppliedPlanSeq;
        this.capacityBytes = capacityBytes;
        this.usedBytes = usedBytes;
        this.createdAt = createdAt;
        this.upstream = copy(upstream);
        this.downstream = copy(downstream);
        this.planResults = copy(planResults);
    }

    @Override
    public String type() {
        return DropboxControl.TYPE_INVENTORY;
    }

    public long getReportSeq() {return reportSeq;}

    public long getLastAppliedPlanSeq() {return lastAppliedPlanSeq;}

    public long getCapacityBytes() {return capacityBytes;}

    public long getUsedBytes() {return usedBytes;}

    /** @return epoch ms when the report was built, or null if the dropbox did not say */
    public Long getCreatedAt() {return createdAt;}

    public List<UpstreamEntry> getUpstream() {return view(upstream);}

    public List<DownstreamEntry> getDownstream() {return view(downstream);}

    public List<PlanResult> getPlanResults() {return view(planResults);}

    @Override
    void validate() throws DropboxControlException {
        requirePositive(reportSeq, "reportSeq");
        requireNonNegative(lastAppliedPlanSeq, "lastAppliedPlanSeq");
        requireNonNegative(capacityBytes, "capacityBytes");
        requireNonNegative(usedBytes, "usedBytes");
        requireCount(upstream, DropboxControl.MAX_ENTRIES_PER_LIST, "upstream");
        requireCount(downstream, DropboxControl.MAX_ENTRIES_PER_LIST, "downstream");
        requireCount(planResults, DropboxControl.MAX_PLAN_RESULTS, "planResults");
        requireNoNullElements(upstream, "upstream");
        requireNoNullElements(downstream, "downstream");
        requireNoNullElements(planResults, "planResults");
        for (UpstreamEntry e : getUpstream()) e.validate();
        for (DownstreamEntry e : getDownstream()) e.validate();
        for (PlanResult r : getPlanResults()) r.validate();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InventoryReport)) return false;
        InventoryReport that = (InventoryReport) o;
        return reportSeq == that.reportSeq && lastAppliedPlanSeq == that.lastAppliedPlanSeq &&
                capacityBytes == that.capacityBytes && usedBytes == that.usedBytes &&
                Objects.equals(createdAt, that.createdAt) && getUpstream().equals(that.getUpstream()) &&
                getDownstream().equals(that.getDownstream()) && getPlanResults().equals(that.getPlanResults());
    }

    @Override
    public int hashCode() {
        return Objects.hash(reportSeq, lastAppliedPlanSeq, capacityBytes, usedBytes, createdAt, getUpstream(),
                            getDownstream(), getPlanResults());
    }

    @Override
    public String toString() {
        return "InventoryReport{reportSeq=" + reportSeq + ", lastAppliedPlanSeq=" + lastAppliedPlanSeq +
                ", usedBytes=" + usedBytes + "/" + capacityBytes + ", upstream=" + getUpstream().size() +
                ", downstream=" + getDownstream().size() + ", planResults=" + getPlanResults().size() + "}";
    }

    /** One client-to-server bundle the dropbox holds for relay. */
    public static final class UpstreamEntry {
        private String bundleId;
        private long sizeBytes;
        private String clientKeyB64;

        private UpstreamEntry() {}

        /**
         * @param bundleId     encrypted bundle ID ({@code bundle.id} jar entry)
         * @param sizeBytes    file size
         * @param clientKeyB64 {@link BundleJarInfo#clientKeyFingerprint(byte[])} of the jar's plaintext
         *                     {@code clientBase.pub}, or null if unreadable. It lets the server map the bundle
         *                     to a client and decide supersession for IDs it never received (Research/R8 §5).
         */
        public UpstreamEntry(String bundleId, long sizeBytes, String clientKeyB64) {
            this.bundleId = bundleId;
            this.sizeBytes = sizeBytes;
            this.clientKeyB64 = clientKeyB64;
        }

        public String getBundleId() {return bundleId;}

        public long getSizeBytes() {return sizeBytes;}

        /** @return the client key fingerprint, or null */
        public String getClientKeyB64() {return clientKeyB64;}

        void validate() throws DropboxControlException {
            requireBundleId(bundleId, "upstream.bundleId");
            requireNonNegative(sizeBytes, "upstream.sizeBytes");
            requireClientKey(clientKeyB64, "upstream.clientKeyB64");
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof UpstreamEntry)) return false;
            UpstreamEntry that = (UpstreamEntry) o;
            return sizeBytes == that.sizeBytes && Objects.equals(bundleId, that.bundleId) &&
                    Objects.equals(clientKeyB64, that.clientKeyB64);
        }

        @Override
        public int hashCode() {return Objects.hash(bundleId, sizeBytes, clientKeyB64);}

        @Override
        public String toString() {return "Upstream{" + bundleId + ", " + sizeBytes + "B}";}
    }

    /** One server-to-client bundle the dropbox holds and serves to clients. */
    public static final class DownstreamEntry {
        private String bundleId;
        private long sizeBytes;
        private String clientKeyB64;
        private long servedCount;
        /** Epoch milliseconds of the last time a client downloaded it, or null if never served. */
        private Long lastServedAt;

        private DownstreamEntry() {}

        /**
         * @param bundleId     encrypted bundle ID ({@code bundle.id} jar entry)
         * @param sizeBytes    file size
         * @param clientKeyB64 fingerprint of the jar's plaintext {@code clientBase.pub} (the server writes the
         *                     addressee's base key into its downstream bundles), or null
         * @param servedCount  how many times clients downloaded it from this dropbox
         * @param lastServedAt epoch ms of the last download, or null if never served
         */
        public DownstreamEntry(String bundleId, long sizeBytes, String clientKeyB64, long servedCount,
                               Long lastServedAt) {
            this.bundleId = bundleId;
            this.sizeBytes = sizeBytes;
            this.clientKeyB64 = clientKeyB64;
            this.servedCount = servedCount;
            this.lastServedAt = lastServedAt;
        }

        public String getBundleId() {return bundleId;}

        public long getSizeBytes() {return sizeBytes;}

        /** @return the client key fingerprint, or null */
        public String getClientKeyB64() {return clientKeyB64;}

        public long getServedCount() {return servedCount;}

        /** @return epoch ms of the last download, or null if never served */
        public Long getLastServedAt() {return lastServedAt;}

        void validate() throws DropboxControlException {
            requireBundleId(bundleId, "downstream.bundleId");
            requireNonNegative(sizeBytes, "downstream.sizeBytes");
            requireClientKey(clientKeyB64, "downstream.clientKeyB64");
            requireNonNegative(servedCount, "downstream.servedCount");
            if (lastServedAt != null) requireNonNegative(lastServedAt, "downstream.lastServedAt");
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DownstreamEntry)) return false;
            DownstreamEntry that = (DownstreamEntry) o;
            return sizeBytes == that.sizeBytes && servedCount == that.servedCount &&
                    Objects.equals(bundleId, that.bundleId) && Objects.equals(clientKeyB64, that.clientKeyB64) &&
                    Objects.equals(lastServedAt, that.lastServedAt);
        }

        @Override
        public int hashCode() {return Objects.hash(bundleId, sizeBytes, clientKeyB64, servedCount, lastServedAt);}

        @Override
        public String toString() {return "Downstream{" + bundleId + ", " + sizeBytes + "B, served=" + servedCount + "}";}
    }

    /**
     * What the dropbox did with one plan: the IDs it deleted and the ones it skipped. Reported until the server
     * has seen them, so the server can confirm its plan took effect.
     */
    public static final class PlanResult {
        private long planSeq;
        private List<String> deleted;
        private List<SkippedEntry> skipped;

        private PlanResult() {}

        /**
         * @param planSeq the applied plan's {@link RetentionPlan#getPlanSeq()}
         * @param deleted bundle IDs deleted (either direction); null means none
         * @param skipped listed IDs not deleted, with why; null means none
         */
        public PlanResult(long planSeq, List<String> deleted, List<SkippedEntry> skipped) {
            this.planSeq = planSeq;
            this.deleted = copy(deleted);
            this.skipped = copy(skipped);
        }

        public long getPlanSeq() {return planSeq;}

        public List<String> getDeleted() {return view(deleted);}

        public List<SkippedEntry> getSkipped() {return view(skipped);}

        void validate() throws DropboxControlException {
            requirePositive(planSeq, "planResults.planSeq");
            requireCount(deleted, DropboxControl.MAX_ENTRIES_PER_LIST, "planResults.deleted");
            requireCount(skipped, DropboxControl.MAX_ENTRIES_PER_LIST, "planResults.skipped");
            for (String id : getDeleted()) requireBundleId(id, "planResults.deleted[]");
            requireNoNullElements(skipped, "planResults.skipped");
            for (SkippedEntry s : getSkipped()) s.validate();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PlanResult)) return false;
            PlanResult that = (PlanResult) o;
            return planSeq == that.planSeq && getDeleted().equals(that.getDeleted()) &&
                    getSkipped().equals(that.getSkipped());
        }

        @Override
        public int hashCode() {return Objects.hash(planSeq, getDeleted(), getSkipped());}

        @Override
        public String toString() {
            return "PlanResult{planSeq=" + planSeq + ", deleted=" + getDeleted().size() + ", skipped=" +
                    getSkipped().size() + "}";
        }
    }

    /** A plan entry the dropbox did not delete. */
    public static final class SkippedEntry {
        private String bundleId;
        private DropboxControl.SkipReason reason;

        private SkippedEntry() {}

        public SkippedEntry(String bundleId, DropboxControl.SkipReason reason) {
            this.bundleId = bundleId;
            this.reason = reason;
        }

        public String getBundleId() {return bundleId;}

        /**
         * @return why it was skipped, or null if the sender used a reason this version does not know (Gson maps
         *         unknown enum names to null; the entry is still valid)
         */
        public DropboxControl.SkipReason getReason() {return reason;}

        void validate() throws DropboxControlException {
            requireBundleId(bundleId, "planResults.skipped.bundleId");
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SkippedEntry)) return false;
            SkippedEntry that = (SkippedEntry) o;
            return Objects.equals(bundleId, that.bundleId) && reason == that.reason;
        }

        @Override
        public int hashCode() {return Objects.hash(bundleId, reason);}

        @Override
        public String toString() {return "Skipped{" + bundleId + ", " + reason + "}";}
    }
}
