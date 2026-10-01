package net.discdd.bundledropbox.cycle;

/**
 * Callback for {@link RadioDriver#startVisit(long, VisitCallback)}.
 *
 * <p>{@link #ready(long)} means discovery has started (this is when the transport dwell timer starts);
 * {@link #failed(long, String)} means discovery could not be started. After {@code ready},
 * {@link #visitFinished(long, boolean)} is called each time a carrier exchange ends.
 */
public interface VisitCallback extends RadioCallback {
    /**
     * A carrier exchange (join, recency fetch, upload, download-all, disconnect) ended.
     *
     * <p>May be called zero or more times per visit: the driver keeps discovering after an exchange
     * until {@link RadioDriver#stopVisit} is called. With {@link CycleConfig#returnAfterVisit()} the
     * controller ends VISIT early on the first {@code success == true}.
     *
     * @param gen     generation passed to {@code startVisit}
     * @param success true if the exchange completed (recency fetch succeeded and transfers finished)
     */
    void visitFinished(long gen, boolean success);
}
