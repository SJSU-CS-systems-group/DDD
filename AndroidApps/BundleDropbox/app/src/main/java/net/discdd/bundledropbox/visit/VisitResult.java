package net.discdd.bundledropbox.visit;

import java.util.Objects;

/**
 * Immutable outcome of one VISIT exchange with a carrier (an unmodified BundleTransport), produced by
 * {@link TransportVisitClient#visit}.
 *
 * <p>Consumers:
 * <ul>
 *   <li>{@code wifi/WifiDirectRadioDriver} reports {@link #ok()} to the role cycle as
 *       {@code VisitCallback.visitFinished(gen, ok)}; with "return after visit" ON a successful visit ends
 *       VISIT mode early.</li>
 *   <li>{@code service/DropboxIdentity} bumps the advertised-ID generation when {@link #recencyUpdated()} is
 *       true, so clients (which only exchange when {@code blobTimestamp > lastExchange} for a transportId,
 *       Research/R3) see the dropbox as a transport with new data.</li>
 *   <li>The UI shows it as {@code DropboxStatus.LastVisit}.</li>
 * </ul>
 *
 * <p>Plain final class rather than a Java record (project rule); record-style accessors.
 */
public final class VisitResult {
    private final String carrierId;
    private final boolean reachable;
    private final boolean recencyFetched;
    private final boolean recencyUpdated;
    private final int uploaded;
    private final int downloaded;
    private final int skipped;
    private final int errors;
    private final boolean stoppedEarly;
    private final String lastError;

    /**
     * @param carrierId      the carrier's DNS-SD {@code transportId} the visit was made to
     * @param reachable      true if the carrier's gRPC port answered the socket probe
     * @param recencyUpdated true if the carrier's recency blob was accepted by the {@code RecencyBlobStore}
     *                       (SUCCESS and strictly newer than the cached one)
     * @param uploaded       UPSTREAM bundles the carrier acknowledged with {@code SUCCESS} during this visit
     * @param downloaded     new DOWNSTREAM bundles committed to the store during this visit
     * @param skipped        downloaded bundles discarded because the store (or this visit) already held that id
     * @param errors         per-bundle or per-RPC failures (including a fatal one, if any)
     * @param stoppedEarly   true if the visit ended before finishing because {@code keepGoing} turned false,
     *                       {@link TransportVisitClient#cancel()} was called, or the thread was interrupted
     * @param lastError      human-readable description of the last error, or {@code null}
     */
    public VisitResult(String carrierId,
                       boolean reachable,
                       boolean recencyUpdated,
                       int uploaded,
                       int downloaded,
                       int skipped,
                       int errors,
                       boolean stoppedEarly,
                       String lastError) {
        this(carrierId, reachable, recencyUpdated, recencyUpdated, uploaded, downloaded, skipped, errors,
             stoppedEarly, lastError);
    }

    /**
     * Full constructor.
     *
     * @param recencyFetched true if the carrier answered {@code getRecencyBlob} with a SUCCESS, server-signed
     *                       blob (whether or not it was newer than the cached one). The service bumps the
     *                       advertised-id generation on this (03 Architecture §5); see the other constructor
     *                       for the remaining parameters
     */
    public VisitResult(String carrierId,
                       boolean reachable,
                       boolean recencyFetched,
                       boolean recencyUpdated,
                       int uploaded,
                       int downloaded,
                       int skipped,
                       int errors,
                       boolean stoppedEarly,
                       String lastError) {
        this.carrierId = Objects.requireNonNull(carrierId, "carrierId");
        this.reachable = reachable;
        this.recencyFetched = recencyFetched || recencyUpdated;
        this.recencyUpdated = recencyUpdated;
        this.uploaded = uploaded;
        this.downloaded = downloaded;
        this.skipped = skipped;
        this.errors = errors;
        this.stoppedEarly = stoppedEarly;
        this.lastError = lastError;
    }

    /** @return the carrier's DNS-SD {@code transportId}. */
    public String carrierId() { return carrierId; }

    /** @return true if the carrier's gRPC port accepted a TCP connection. */
    public boolean reachable() { return reachable; }

    /**
     * @return true if the carrier returned a SUCCESS recency blob (stored or not). Basis for advertised-id
     *         rotation: the carrier is in sync with the server, so what we now hold is as fresh as it gets.
     */
    public boolean recencyFetched() { return recencyFetched; }

    /** @return true if a newer SUCCESS recency blob was stored during this visit. */
    public boolean recencyUpdated() { return recencyUpdated; }

    /** @return number of UPSTREAM bundles acknowledged by the carrier. */
    public int uploaded() { return uploaded; }

    /** @return number of new DOWNSTREAM bundles committed. */
    public int downloaded() { return downloaded; }

    /** @return number of downloaded bundles discarded as already held (the cost of v1 download-all). */
    public int skipped() { return skipped; }

    /** @return number of errors encountered. */
    public int errors() { return errors; }

    /** @return true if the visit was stopped (keepGoing false, cancel, interrupt) before it finished. */
    public boolean stoppedEarly() { return stoppedEarly; }

    /** @return description of the last error, or {@code null} if none. */
    public String lastError() { return lastError; }

    /**
     * @return true if the carrier was reached, every phase (recency, upload, download-all) ran to completion,
     *         and no error occurred. This is the {@code success} flag for {@code VisitCallback.visitFinished}.
     *         A false result is safe: nothing is marked forwarded unless acknowledged and partial downloads are
     *         discarded, so the next visit simply retries.
     */
    public boolean ok() {
        return reachable && !stoppedEarly && errors == 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VisitResult)) return false;
        VisitResult that = (VisitResult) o;
        return reachable == that.reachable && recencyFetched == that.recencyFetched &&
                recencyUpdated == that.recencyUpdated && uploaded == that.uploaded &&
                downloaded == that.downloaded && skipped == that.skipped && errors == that.errors &&
                stoppedEarly == that.stoppedEarly && carrierId.equals(that.carrierId) &&
                Objects.equals(lastError, that.lastError);
    }

    @Override
    public int hashCode() {
        return Objects.hash(carrierId,
                            reachable,
                            recencyFetched,
                            recencyUpdated,
                            uploaded,
                            downloaded,
                            skipped,
                            errors,
                            stoppedEarly,
                            lastError);
    }

    @Override
    public String toString() {
        return "VisitResult{carrier=" + carrierId + ", ok=" + ok() + ", reachable=" + reachable + ", recencyFetched=" + recencyFetched + ", recencyUpdated=" +
                recencyUpdated + ", uploaded=" + uploaded + ", downloaded=" + downloaded + ", skipped=" + skipped +
                ", errors=" + errors + ", stoppedEarly=" + stoppedEarly +
                (lastError == null ? "" : ", lastError=" + lastError) + "}";
    }
}
