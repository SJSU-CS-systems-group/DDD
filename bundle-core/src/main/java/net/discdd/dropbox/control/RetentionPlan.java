package net.discdd.dropbox.control;

import java.util.List;
import java.util.Objects;

/**
 * Server to dropbox ({@code "type":"plan"}): the exact bundle IDs the dropbox may delete, per direction, each with
 * the fact that justifies it.
 *
 * <p>Plans are replay-safe: {@link #getPlanSeq()} is monotonic per dropbox, the dropbox applies a plan only when
 * its sequence is higher than the last one applied, and every {@link DropboxControl.Reason} is a monotonic fact,
 * so deleting a listed ID is correct whenever the plan arrives. A listed ID the dropbox does not hold in that
 * direction is reported back as {@link DropboxControl.SkipReason#NOT_FOUND}, never treated as an error.
 */
public final class RetentionPlan extends DropboxControlMessage {
    private long planSeq;
    private long basedOnReportSeq;
    /** Epoch milliseconds when the server computed the plan; optional, informational only. */
    private Long createdAt;
    private List<Deletion> deleteUpstream;
    private List<Deletion> deleteDownstream;
    /** Optional: IDs the server decided to keep, for transparency. Null means "not reported". */
    private List<String> keep;

    /** For Gson. */
    private RetentionPlan() {}

    /**
     * @param planSeq          monotonic per dropbox, starting at 1
     * @param basedOnReportSeq the {@link InventoryReport#getReportSeq()} the plan was computed from
     * @param createdAt        epoch ms when the plan was computed, or null
     * @param deleteUpstream   upstream IDs to delete (null means none)
     * @param deleteDownstream downstream IDs to delete (null means none)
     * @param keep             IDs explicitly kept, or null to omit the field
     */
    public RetentionPlan(long planSeq, long basedOnReportSeq, Long createdAt, List<Deletion> deleteUpstream,
                         List<Deletion> deleteDownstream, List<String> keep) {
        this.planSeq = planSeq;
        this.basedOnReportSeq = basedOnReportSeq;
        this.createdAt = createdAt;
        this.deleteUpstream = copy(deleteUpstream);
        this.deleteDownstream = copy(deleteDownstream);
        this.keep = copyOrNull(keep);
    }

    @Override
    public String type() {
        return DropboxControl.TYPE_PLAN;
    }

    public long getPlanSeq() {return planSeq;}

    public long getBasedOnReportSeq() {return basedOnReportSeq;}

    /** @return epoch ms when the plan was computed, or null */
    public Long getCreatedAt() {return createdAt;}

    public List<Deletion> getDeleteUpstream() {return view(deleteUpstream);}

    public List<Deletion> getDeleteDownstream() {return view(deleteDownstream);}

    /** @return IDs the server chose to keep (empty if the field was omitted) */
    public List<String> getKeep() {return view(keep);}

    @Override
    void validate() throws DropboxControlException {
        requirePositive(planSeq, "planSeq");
        requireNonNegative(basedOnReportSeq, "basedOnReportSeq");
        requireCount(deleteUpstream, DropboxControl.MAX_ENTRIES_PER_LIST, "deleteUpstream");
        requireCount(deleteDownstream, DropboxControl.MAX_ENTRIES_PER_LIST, "deleteDownstream");
        requireCount(keep, DropboxControl.MAX_ENTRIES_PER_LIST, "keep");
        requireNoNullElements(deleteUpstream, "deleteUpstream");
        requireNoNullElements(deleteDownstream, "deleteDownstream");
        for (Deletion d : getDeleteUpstream()) d.validate("deleteUpstream");
        for (Deletion d : getDeleteDownstream()) d.validate("deleteDownstream");
        for (String id : getKeep()) requireBundleId(id, "keep[]");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RetentionPlan)) return false;
        RetentionPlan that = (RetentionPlan) o;
        return planSeq == that.planSeq && basedOnReportSeq == that.basedOnReportSeq &&
                Objects.equals(createdAt, that.createdAt) && getDeleteUpstream().equals(that.getDeleteUpstream()) &&
                getDeleteDownstream().equals(that.getDeleteDownstream()) && getKeep().equals(that.getKeep());
    }

    @Override
    public int hashCode() {
        return Objects.hash(planSeq, basedOnReportSeq, createdAt, getDeleteUpstream(), getDeleteDownstream(),
                            getKeep());
    }

    @Override
    public String toString() {
        return "RetentionPlan{planSeq=" + planSeq + ", basedOnReportSeq=" + basedOnReportSeq + ", deleteUpstream=" +
                getDeleteUpstream().size() + ", deleteDownstream=" + getDeleteDownstream().size() + ", keep=" +
                getKeep().size() + "}";
    }

    /** One bundle the dropbox may delete, and why. */
    public static final class Deletion {
        private String bundleId;
        private DropboxControl.Reason reason;

        private Deletion() {}

        public Deletion(String bundleId, DropboxControl.Reason reason) {
            this.bundleId = bundleId;
            this.reason = reason;
        }

        public String getBundleId() {return bundleId;}

        /**
         * @return the justification, or null if the server used a reason this version does not know. Gson maps
         *         unknown enum names to null; the deletion is still valid, because the reason is informational
         *         and the dropbox deletes exactly the listed IDs regardless.
         */
        public DropboxControl.Reason getReason() {return reason;}

        void validate(String list) throws DropboxControlException {
            requireBundleId(bundleId, list + ".bundleId");
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Deletion)) return false;
            Deletion that = (Deletion) o;
            return Objects.equals(bundleId, that.bundleId) && reason == that.reason;
        }

        @Override
        public int hashCode() {return Objects.hash(bundleId, reason);}

        @Override
        public String toString() {return "Deletion{" + bundleId + ", " + reason + "}";}
    }
}
