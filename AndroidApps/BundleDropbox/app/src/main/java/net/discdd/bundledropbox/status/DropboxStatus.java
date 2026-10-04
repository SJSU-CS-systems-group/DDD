package net.discdd.bundledropbox.status;

import net.discdd.bundledropbox.cycle.CycleListener;
import net.discdd.bundledropbox.cycle.CycleMode;
import net.discdd.bundledropbox.store.StoreStats;

import java.util.Objects;

/**
 * Immutable snapshot of everything the UI shows about the dropbox. Published through the Kotlin
 * {@code net.discdd.bundledropbox.DropboxStatusRepository} (a {@code StateFlow}), which plays the role
 * {@code DDDWifiServiceEvents} plays in the transport.
 *
 * <p>Producers (service, cycle listener, RPC server, visit worker) derive a new value with
 * {@link #toBuilder()} and publish it with {@code DropboxStatusRepository.modify(...)} so concurrent
 * updates do not lose each other's fields.
 *
 * <p>Time bases:
 * <ul>
 *   <li>{@link #modeStartedAtMillis()} and {@link #deadlineAtMillis()} are monotonic
 *       ({@code SystemClock.elapsedRealtime}, the cycle {@code Clock}); the UI computes countdowns with
 *       {@code deadlineAtMillis - SystemClock.elapsedRealtime()}.</li>
 *   <li>{@link #recencyBlobTimestamp()} is the server's wall clock (epoch millis).</li>
 *   <li>{@link LastVisit#endedAtMillis()} is the local wall clock (epoch millis).</li>
 * </ul>
 */
public final class DropboxStatus {
    /** Status before the service has reported anything. */
    public static final DropboxStatus INITIAL = new Builder().build();

    private final CycleMode mode;
    private final long modeStartedAtMillis;
    private final long deadlineAtMillis;
    private final String advertisedId;
    private final long recencyBlobTimestamp;
    private final int activeSessions;
    private final int upstreamCount;
    private final long upstreamBytes;
    private final int downstreamCount;
    private final long downstreamBytes;
    private final LastVisit lastVisit;
    private final String lastError;
    private final ControlStatus control;

    private DropboxStatus(Builder b) {
        this.mode = Objects.requireNonNull(b.mode, "mode");
        this.modeStartedAtMillis = b.modeStartedAtMillis;
        this.deadlineAtMillis = b.deadlineAtMillis;
        this.advertisedId = b.advertisedId;
        this.recencyBlobTimestamp = b.recencyBlobTimestamp;
        this.activeSessions = b.activeSessions;
        this.upstreamCount = b.upstreamCount;
        this.upstreamBytes = b.upstreamBytes;
        this.downstreamCount = b.downstreamCount;
        this.downstreamBytes = b.downstreamBytes;
        this.lastVisit = b.lastVisit;
        this.lastError = b.lastError;
        this.control = Objects.requireNonNull(b.control, "control");
    }

    /** @return a builder with every field at its {@link #INITIAL} value. */
    public static Builder builder() { return new Builder(); }

    /** @return a builder pre-filled with this snapshot's values. */
    public Builder toBuilder() { return new Builder(this); }

    /** @return current role-cycle mode; {@link CycleMode#STOPPED} until the controller starts. */
    public CycleMode mode() { return mode; }

    /** @return monotonic time the current mode was entered, or 0 if unknown. */
    public long modeStartedAtMillis() { return modeStartedAtMillis; }

    /** @return monotonic time the current mode is due to end, or {@link CycleListener#NO_DEADLINE}. */
    public long deadlineAtMillis() { return deadlineAtMillis; }

    /** @return true if {@link #deadlineAtMillis()} is set. */
    public boolean hasDeadline() { return deadlineAtMillis != CycleListener.NO_DEADLINE; }

    /**
     * @return the {@code transportId} currently advertised to clients ({@code baseId-gen} when rotation
     *         is on), or {@code null} before the identity is loaded
     */
    public String advertisedId() { return advertisedId; }

    /** @return {@code blobTimestamp} of the cached recency blob (server wall clock), or 0 if none. */
    public long recencyBlobTimestamp() { return recencyBlobTimestamp; }

    /** @return true if a recency blob is cached, i.e. the dropbox can serve clients. */
    public boolean hasRecencyBlob() { return recencyBlobTimestamp > 0; }

    /** @return client sessions currently admitted by the activity gate. */
    public int activeSessions() { return activeSessions; }

    /** @return committed client-to-server bundles. */
    public int upstreamCount() { return upstreamCount; }

    /** @return committed client-to-server bytes. */
    public long upstreamBytes() { return upstreamBytes; }

    /** @return committed server-to-client bundles. */
    public int downstreamCount() { return downstreamCount; }

    /** @return committed server-to-client bytes. */
    public long downstreamBytes() { return downstreamBytes; }

    /** @return the most recent carrier exchange, or {@code null} if none happened since start. */
    public LastVisit lastVisit() { return lastVisit; }

    /** @return the most recent error message for the UI, or {@code null}. */
    public String lastError() { return lastError; }

    /** @return the dropbox's control channel (server-managed storage); {@link ControlStatus#NONE} before start. */
    public ControlStatus control() { return control; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DropboxStatus)) return false;
        DropboxStatus s = (DropboxStatus) o;
        return modeStartedAtMillis == s.modeStartedAtMillis && deadlineAtMillis == s.deadlineAtMillis &&
                recencyBlobTimestamp == s.recencyBlobTimestamp && activeSessions == s.activeSessions &&
                upstreamCount == s.upstreamCount && upstreamBytes == s.upstreamBytes &&
                downstreamCount == s.downstreamCount && downstreamBytes == s.downstreamBytes && mode == s.mode &&
                Objects.equals(advertisedId, s.advertisedId) && Objects.equals(lastVisit, s.lastVisit) &&
                Objects.equals(lastError, s.lastError) && control.equals(s.control);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, modeStartedAtMillis, deadlineAtMillis, advertisedId, recencyBlobTimestamp,
                            activeSessions, upstreamCount, upstreamBytes, downstreamCount, downstreamBytes,
                            lastVisit, lastError, control);
    }

    @Override
    public String toString() {
        return "DropboxStatus{mode=" + mode + ", startedAt=" + modeStartedAtMillis + ", deadline=" +
                deadlineAtMillis + ", advertisedId=" + advertisedId + ", blobTs=" + recencyBlobTimestamp +
                ", sessions=" + activeSessions + ", up=" + upstreamCount + "/" + upstreamBytes + "B, down=" +
                downstreamCount + "/" + downstreamBytes + "B, lastVisit=" + lastVisit + ", lastError=" + lastError +
                ", control=" + control + "}";
    }

    /** Mutable builder for {@link DropboxStatus}. Not thread-safe; use one per update. */
    public static final class Builder {
        private CycleMode mode = CycleMode.STOPPED;
        private long modeStartedAtMillis = 0;
        private long deadlineAtMillis = CycleListener.NO_DEADLINE;
        private String advertisedId = null;
        private long recencyBlobTimestamp = 0;
        private int activeSessions = 0;
        private int upstreamCount = 0;
        private long upstreamBytes = 0;
        private int downstreamCount = 0;
        private long downstreamBytes = 0;
        private LastVisit lastVisit = null;
        private String lastError = null;
        private ControlStatus control = ControlStatus.NONE;

        private Builder() {
        }

        private Builder(DropboxStatus s) {
            this.mode = s.mode;
            this.modeStartedAtMillis = s.modeStartedAtMillis;
            this.deadlineAtMillis = s.deadlineAtMillis;
            this.advertisedId = s.advertisedId;
            this.recencyBlobTimestamp = s.recencyBlobTimestamp;
            this.activeSessions = s.activeSessions;
            this.upstreamCount = s.upstreamCount;
            this.upstreamBytes = s.upstreamBytes;
            this.downstreamCount = s.downstreamCount;
            this.downstreamBytes = s.downstreamBytes;
            this.lastVisit = s.lastVisit;
            this.lastError = s.lastError;
            this.control = s.control;
        }

        /** Sets the mode. */
        public Builder mode(CycleMode v) { this.mode = Objects.requireNonNull(v, "mode"); return this; }

        /** Sets the monotonic mode start time. */
        public Builder modeStartedAtMillis(long v) { this.modeStartedAtMillis = v; return this; }

        /** Sets the monotonic deadline, or {@link CycleListener#NO_DEADLINE}. */
        public Builder deadlineAtMillis(long v) { this.deadlineAtMillis = v; return this; }

        /** Convenience for a {@link CycleListener#onModeChanged} update: mode, start and deadline at once. */
        public Builder cycle(CycleMode mode, long modeStartedAtMillis, long deadlineAtMillis) {
            return mode(mode).modeStartedAtMillis(modeStartedAtMillis).deadlineAtMillis(deadlineAtMillis);
        }

        /** Sets the advertised transport id. */
        public Builder advertisedId(String v) { this.advertisedId = v; return this; }

        /** Sets the cached recency blob timestamp (0 = none). */
        public Builder recencyBlobTimestamp(long v) { this.recencyBlobTimestamp = v; return this; }

        /** Sets the number of admitted client sessions. */
        public Builder activeSessions(int v) { this.activeSessions = v; return this; }

        /** Sets the upstream count. */
        public Builder upstreamCount(int v) { this.upstreamCount = v; return this; }

        /** Sets the upstream bytes. */
        public Builder upstreamBytes(long v) { this.upstreamBytes = v; return this; }

        /** Sets the downstream count. */
        public Builder downstreamCount(int v) { this.downstreamCount = v; return this; }

        /** Sets the downstream bytes. */
        public Builder downstreamBytes(long v) { this.downstreamBytes = v; return this; }

        /** Copies the four queue counters from a {@code RelayStore.stats()} snapshot. */
        public Builder storeStats(StoreStats s) {
            return upstreamCount(s.upstreamCount()).upstreamBytes(s.upstreamBytes())
                    .downstreamCount(s.downstreamCount()).downstreamBytes(s.downstreamBytes());
        }

        /** Sets the last carrier visit ({@code null} = none). */
        public Builder lastVisit(LastVisit v) { this.lastVisit = v; return this; }

        /** Sets the last error message ({@code null} clears it). */
        public Builder lastError(String v) { this.lastError = v; return this; }

        /** Sets the control-channel status ({@code null} means {@link ControlStatus#NONE}). */
        public Builder control(ControlStatus v) { this.control = v == null ? ControlStatus.NONE : v; return this; }

        /** @return the immutable snapshot */
        public DropboxStatus build() { return new DropboxStatus(this); }
    }

    /** Summary of the most recent exchange with a carrier (BundleTransport) during VISIT mode. */
    public static final class LastVisit {
        private final String carrierId;
        private final int uploaded;
        private final int downloaded;
        private final long endedAtMillis;
        private final boolean ok;

        /**
         * @param carrierId     the carrier's DNS-SD {@code transportId}
         * @param uploaded      upstream bundles the carrier acknowledged during this visit
         * @param downloaded    new downstream bundles committed during this visit
         * @param endedAtMillis local wall-clock time the visit ended (epoch millis)
         * @param ok            true if the exchange completed without error
         */
        public LastVisit(String carrierId, int uploaded, int downloaded, long endedAtMillis, boolean ok) {
            this.carrierId = carrierId;
            this.uploaded = uploaded;
            this.downloaded = downloaded;
            this.endedAtMillis = endedAtMillis;
            this.ok = ok;
        }

        /** @return carrier {@code transportId}. */
        public String carrierId() { return carrierId; }

        /** @return bundles uploaded to the carrier. */
        public int uploaded() { return uploaded; }

        /** @return new bundles downloaded from the carrier. */
        public int downloaded() { return downloaded; }

        /** @return wall-clock end time, epoch millis. */
        public long endedAtMillis() { return endedAtMillis; }

        /** @return whether the exchange succeeded. */
        public boolean ok() { return ok; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LastVisit)) return false;
            LastVisit v = (LastVisit) o;
            return uploaded == v.uploaded && downloaded == v.downloaded && endedAtMillis == v.endedAtMillis &&
                    ok == v.ok && Objects.equals(carrierId, v.carrierId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(carrierId, uploaded, downloaded, endedAtMillis, ok);
        }

        @Override
        public String toString() {
            return "LastVisit{" + carrierId + ", up=" + uploaded + ", down=" + downloaded + ", endedAt=" +
                    endedAtMillis + ", ok=" + ok + "}";
        }
    }
}
