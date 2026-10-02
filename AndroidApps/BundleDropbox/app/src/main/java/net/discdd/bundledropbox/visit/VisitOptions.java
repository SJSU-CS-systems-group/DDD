package net.discdd.bundledropbox.visit;

/**
 * Tunables for {@link TransportVisitClient}: probe retries, per-RPC deadlines and download-all bounds.
 *
 * <p>{@link #DEFAULTS} mirror the stock client so the dropbox behaves like a client toward the carrier
 * (Research/R2 §2): 8 probe attempts 500 ms apart ({@code ClientBundleTransmission.INITIAL_CONNECT_RETRIES}),
 * 5 s for {@code getRecencyBlob} ({@code Constants.GRPC_SHORT_TIMEOUT_MS}) and 120 s for upload, PSI and
 * download RPCs ({@code Constants.GRPC_LONG_TIMEOUT_MS}). Tests shrink them with the {@code with*} methods.
 *
 * <p>Immutable; plain final class (project rule: no records).
 */
public final class VisitOptions {
    /** Stock-client parity defaults. */
    public static final VisitOptions DEFAULTS = new VisitOptions(8, 500, 1_000, 5_000, 120_000, 2);

    private final int probeAttempts;
    private final long probeDelayMs;
    private final int probeConnectTimeoutMs;
    private final long shortDeadlineMs;
    private final long longDeadlineMs;
    private final int maxDownloadPasses;

    /**
     * @param probeAttempts         TCP connect attempts before giving up on the carrier (at least 1)
     * @param probeDelayMs          sleep between failed probe attempts
     * @param probeConnectTimeoutMs connect timeout of each probe attempt
     * @param shortDeadlineMs       deadline for {@code getRecencyBlob}
     * @param longDeadlineMs        deadline for each {@code uploadBundle}, {@code psiExchange} and
     *                              {@code psiDownloadBundle} call
     * @param maxDownloadPasses     upper bound on download-all passes per visit (at least 1); a second pass only
     *                              runs when the carrier's listing changed during the first one
     */
    public VisitOptions(int probeAttempts,
                        long probeDelayMs,
                        int probeConnectTimeoutMs,
                        long shortDeadlineMs,
                        long longDeadlineMs,
                        int maxDownloadPasses) {
        if (probeAttempts < 1) throw new IllegalArgumentException("probeAttempts must be >= 1");
        if (probeDelayMs < 0) throw new IllegalArgumentException("probeDelayMs must be >= 0");
        if (probeConnectTimeoutMs < 1) throw new IllegalArgumentException("probeConnectTimeoutMs must be >= 1");
        if (shortDeadlineMs < 1 || longDeadlineMs < 1) throw new IllegalArgumentException("deadlines must be > 0");
        if (maxDownloadPasses < 1) throw new IllegalArgumentException("maxDownloadPasses must be >= 1");
        this.probeAttempts = probeAttempts;
        this.probeDelayMs = probeDelayMs;
        this.probeConnectTimeoutMs = probeConnectTimeoutMs;
        this.shortDeadlineMs = shortDeadlineMs;
        this.longDeadlineMs = longDeadlineMs;
        this.maxDownloadPasses = maxDownloadPasses;
    }

    /** @return TCP probe attempts. */
    public int probeAttempts() { return probeAttempts; }

    /** @return delay between probe attempts in ms. */
    public long probeDelayMs() { return probeDelayMs; }

    /** @return connect timeout per probe attempt in ms. */
    public int probeConnectTimeoutMs() { return probeConnectTimeoutMs; }

    /** @return deadline for {@code getRecencyBlob} in ms. */
    public long shortDeadlineMs() { return shortDeadlineMs; }

    /** @return deadline for upload, PSI exchange and PSI download calls in ms. */
    public long longDeadlineMs() { return longDeadlineMs; }

    /** @return maximum download-all passes per visit. */
    public int maxDownloadPasses() { return maxDownloadPasses; }

    /** @return a copy with different probe settings. */
    public VisitOptions withProbe(int attempts, long delayMs) {
        return new VisitOptions(attempts, delayMs, probeConnectTimeoutMs, shortDeadlineMs, longDeadlineMs,
                                maxDownloadPasses);
    }

    /** @return a copy with different RPC deadlines. */
    public VisitOptions withDeadlines(long shortMs, long longMs) {
        return new VisitOptions(probeAttempts, probeDelayMs, probeConnectTimeoutMs, shortMs, longMs,
                                maxDownloadPasses);
    }

    /** @return a copy with a different download-pass bound. */
    public VisitOptions withMaxDownloadPasses(int passes) {
        return new VisitOptions(probeAttempts, probeDelayMs, probeConnectTimeoutMs, shortDeadlineMs, longDeadlineMs,
                                passes);
    }

    @Override
    public String toString() {
        return "VisitOptions{probe=" + probeAttempts + "x" + probeDelayMs + "ms, connectTimeout=" +
                probeConnectTimeoutMs + "ms, short=" + shortDeadlineMs + "ms, long=" + longDeadlineMs +
                "ms, passes=" + maxDownloadPasses + "}";
    }
}
