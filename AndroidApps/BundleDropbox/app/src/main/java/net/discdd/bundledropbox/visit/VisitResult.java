package net.discdd.bundledropbox.visit;

import java.util.Objects;

/**
 * Immutable outcome of one VISIT exchange with a carrier (an unmodified BundleTransport), produced by
 * {@link TransportVisitClient#visit}.
 *
 * <p>Consumers:
 * <ul>
 *   <li>{@code wifi/WifiDirectRadioDriver} reports the service's verdict ({@code service/VisitCoordinator}, based
 *       on {@link #ok()}) to the role cycle as {@code VisitCallback.visitFinished(gen, ok)}; with "return after
 *       visit" ON a successful visit ends VISIT mode early.</li>
 *   <li>The UI shows it as {@code DropboxStatus.LastVisit}.</li>
 * </ul>
 *
 * <p>Nothing here says whether the dropbox got a recency blob: the carrier's blob is never stored (it names the
 * carrier), and the dropbox's own blob arrives as a control grant, counted in {@link #controlProcessed()}
 * (09 Own Recency Blob Design).
 *
 * <p>The control-channel counters ({@link #controlUploaded()}, {@link #controlProcessed()}) count the dropbox's
 * <em>own</em> bundles, which never enter the relay store; {@link #uploaded()}, {@link #downloaded()} and
 * {@link #skipped()} count relayed bundles only.
 *
 * <p>Plain final class rather than a Java record (project rule); record-style accessors.
 */
public final class VisitResult {
    private final String carrierId;
    private final boolean reachable;
    private final boolean carrierTrusted;
    private final int uploaded;
    private final int downloaded;
    private final int skipped;
    private final int errors;
    private final boolean stoppedEarly;
    private final String lastError;
    private final int controlUploaded;
    private final int controlProcessed;

    /**
     * @param carrierId      the carrier's DNS-SD {@code transportId} the visit was made to
     * @param reachable      true if the carrier's gRPC port answered the socket probe
     * @param carrierTrusted true if the carrier answered {@code getRecencyBlob} with a blob signed by the server
     *                       the dropbox's clients trust (SUCCESS, pinned key, valid signature, not from the
     *                       future), so its {@code senderId} could name the carrier in routing metadata. The
     *                       blob itself is never stored
     * @param uploaded       UPSTREAM bundles the carrier acknowledged with {@code SUCCESS} during this visit
     * @param downloaded     new DOWNSTREAM bundles committed to the store during this visit
     * @param skipped        downloaded carrier files discarded: the store (or this visit) already held that id, or
     *                       the file was empty / not a bundle jar (transient on a stock carrier, not an error)
     * @param errors         per-bundle or per-RPC failures (including a fatal one, if any)
     * @param stoppedEarly   true if the visit ended before finishing because {@code keepGoing} turned false,
     *                       {@link TransportVisitClient#cancel()} was called, or the thread was interrupted
     * @param lastError      human-readable description of the last error, or {@code null}
     */
    public VisitResult(String carrierId,
                       boolean reachable,
                       boolean carrierTrusted,
                       int uploaded,
                       int downloaded,
                       int skipped,
                       int errors,
                       boolean stoppedEarly,
                       String lastError) {
        this.carrierId = Objects.requireNonNull(carrierId, "carrierId");
        this.reachable = reachable;
        this.carrierTrusted = carrierTrusted;
        this.uploaded = uploaded;
        this.downloaded = downloaded;
        this.skipped = skipped;
        this.errors = errors;
        this.stoppedEarly = stoppedEarly;
        this.lastError = lastError;
        this.controlUploaded = 0;
        this.controlProcessed = 0;
    }

    private VisitResult(VisitResult base, int controlUploaded, int controlProcessed) {
        this.carrierId = base.carrierId;
        this.reachable = base.reachable;
        this.carrierTrusted = base.carrierTrusted;
        this.uploaded = base.uploaded;
        this.downloaded = base.downloaded;
        this.skipped = base.skipped;
        this.errors = base.errors;
        this.stoppedEarly = base.stoppedEarly;
        this.lastError = base.lastError;
        this.controlUploaded = controlUploaded;
        this.controlProcessed = controlProcessed;
    }

    /**
     * @param controlUploaded  the dropbox's own control bundles the carrier acknowledged during this visit
     * @param controlProcessed bundles addressed to the dropbox that its control endpoint processed
     * @return a copy of this result carrying the control-channel counters
     */
    public VisitResult withControl(int controlUploaded, int controlProcessed) {
        return new VisitResult(this, controlUploaded, controlProcessed);
    }

    /** @return the carrier's DNS-SD {@code transportId}. */
    public String carrierId() { return carrierId; }

    /** @return true if the carrier's gRPC port accepted a TCP connection. */
    public boolean reachable() { return reachable; }

    /**
     * @return true if the carrier served a recency blob signed by the server our clients trust: it has synced with
     *         our server, and its {@code senderId} was used to name it in the dropbox endpoint's routing metadata.
     *         Informational only (logs, tests); the blob was not stored.
     */
    public boolean carrierTrusted() { return carrierTrusted; }

    /** @return number of UPSTREAM bundles acknowledged by the carrier. */
    public int uploaded() { return uploaded; }

    /** @return number of new DOWNSTREAM bundles committed. */
    public int downloaded() { return downloaded; }

    /**
     * @return number of downloaded carrier files discarded: already held (the cost of v1 download-all), or empty /
     *         not a bundle jar
     */
    public int skipped() { return skipped; }

    /** @return number of errors encountered. */
    public int errors() { return errors; }

    /** @return true if the visit was stopped (keepGoing false, cancel, interrupt) before it finished. */
    public boolean stoppedEarly() { return stoppedEarly; }

    /** @return description of the last error, or {@code null} if none. */
    public String lastError() { return lastError; }

    /** @return the dropbox's own control bundles (inventory reports) the carrier acknowledged. */
    public int controlUploaded() { return controlUploaded; }

    /** @return bundles addressed to the dropbox (retention plans) processed by its control endpoint. */
    public int controlProcessed() { return controlProcessed; }

    /**
     * @return true if the carrier was reached, every phase (recency probe, upload, download-all) ran to
     *         completion, and no error occurred. A carrier without a blob of our server is not an error. The
     *         service's {@code success} flag for {@code VisitCallback.visitFinished} starts from this. A false
     *         result is safe: nothing is marked forwarded unless acknowledged and partial downloads are
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
        return reachable == that.reachable && carrierTrusted == that.carrierTrusted && uploaded == that.uploaded &&
                downloaded == that.downloaded && skipped == that.skipped && errors == that.errors &&
                stoppedEarly == that.stoppedEarly && controlUploaded == that.controlUploaded &&
                controlProcessed == that.controlProcessed && carrierId.equals(that.carrierId) &&
                Objects.equals(lastError, that.lastError);
    }

    @Override
    public int hashCode() {
        return Objects.hash(carrierId,
                            reachable,
                            carrierTrusted,
                            uploaded,
                            downloaded,
                            skipped,
                            errors,
                            stoppedEarly,
                            lastError,
                            controlUploaded,
                            controlProcessed);
    }

    @Override
    public String toString() {
        return "VisitResult{carrier=" + carrierId + ", ok=" + ok() + ", reachable=" + reachable +
                ", carrierTrusted=" + carrierTrusted + ", uploaded=" + uploaded + ", downloaded=" + downloaded +
                ", skipped=" + skipped +
                ", errors=" + errors + ", stoppedEarly=" + stoppedEarly + ", controlUploaded=" + controlUploaded +
                ", controlProcessed=" + controlProcessed +
                (lastError == null ? "" : ", lastError=" + lastError) + "}";
    }
}
