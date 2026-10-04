package net.discdd.bundledropbox.cycle;

/**
 * Immutable timer settings for the role cycle. Pure Java so it can be unit tested; reading it from
 * SharedPreferences lives in {@code net.discdd.bundledropbox.DropboxPrefs}.
 *
 * <p>Defaults come from 02 Decisions: client-serving dwell 60 s, transport search/exchange dwell 30 s,
 * drain cap 15 s, return early after a successful visit. Dwell timers start only when the radio reports
 * the mode is ready; the drain cap is measured from the start of the drain (the dwell expiry, or the moment
 * a late expiry is detected) and is never extended.
 *
 * <p>Change at runtime with withers and {@code RoleCycleController.updateConfig(CycleConfig)}.
 */
public final class CycleConfig {
    /** Default client-serving (HOST) dwell: 60 s. */
    public static final long DEFAULT_CLIENT_PERIOD_MS = 60_000L;
    /** Default transport search/exchange (VISIT) dwell: 30 s. */
    public static final long DEFAULT_TRANSPORT_PERIOD_MS = 30_000L;
    /** Default maximum time to finish in-flight work after a dwell expires: 15 s. */
    public static final long DEFAULT_DRAIN_CAP_MS = 15_000L;
    /** Default for ending VISIT early after a successful carrier exchange. */
    public static final boolean DEFAULT_RETURN_AFTER_VISIT = true;

    /** Configuration with every default. */
    public static final CycleConfig DEFAULTS = new CycleConfig(DEFAULT_CLIENT_PERIOD_MS,
                                                               DEFAULT_TRANSPORT_PERIOD_MS,
                                                               DEFAULT_DRAIN_CAP_MS,
                                                               DEFAULT_RETURN_AFTER_VISIT);

    private final long clientPeriodMs;
    private final long transportPeriodMs;
    private final long drainCapMs;
    private final boolean returnAfterVisit;

    /**
     * @param clientPeriodMs    HOSTING dwell in ms, must be &gt; 0
     * @param transportPeriodMs VISITING dwell in ms, must be &gt; 0
     * @param drainCapMs        drain cap in ms, must be &gt;= 0 (0 = cancel in-flight work immediately)
     * @param returnAfterVisit  if true, leave VISIT as soon as a carrier exchange completes successfully
     * @throws IllegalArgumentException if a duration is out of range
     */
    public CycleConfig(long clientPeriodMs, long transportPeriodMs, long drainCapMs, boolean returnAfterVisit) {
        if (clientPeriodMs <= 0) throw new IllegalArgumentException("clientPeriodMs must be > 0: " + clientPeriodMs);
        if (transportPeriodMs <= 0) {
            throw new IllegalArgumentException("transportPeriodMs must be > 0: " + transportPeriodMs);
        }
        if (drainCapMs < 0) throw new IllegalArgumentException("drainCapMs must be >= 0: " + drainCapMs);
        this.clientPeriodMs = clientPeriodMs;
        this.transportPeriodMs = transportPeriodMs;
        this.drainCapMs = drainCapMs;
        this.returnAfterVisit = returnAfterVisit;
    }

    /** @return HOSTING dwell in ms. */
    public long clientPeriodMs() { return clientPeriodMs; }

    /** @return VISITING dwell in ms. */
    public long transportPeriodMs() { return transportPeriodMs; }

    /** @return drain cap in ms, measured from the start of the drain (see class docs). */
    public long drainCapMs() { return drainCapMs; }

    /** @return whether VISIT ends early after a successful carrier exchange. */
    public boolean returnAfterVisit() { return returnAfterVisit; }

    /** @return a copy with a new HOSTING dwell. */
    public CycleConfig withClientPeriodMs(long ms) {
        return new CycleConfig(ms, transportPeriodMs, drainCapMs, returnAfterVisit);
    }

    /** @return a copy with a new VISITING dwell. */
    public CycleConfig withTransportPeriodMs(long ms) {
        return new CycleConfig(clientPeriodMs, ms, drainCapMs, returnAfterVisit);
    }

    /** @return a copy with a new drain cap. */
    public CycleConfig withDrainCapMs(long ms) {
        return new CycleConfig(clientPeriodMs, transportPeriodMs, ms, returnAfterVisit);
    }

    /** @return a copy with a new early-return flag. */
    public CycleConfig withReturnAfterVisit(boolean value) {
        return new CycleConfig(clientPeriodMs, transportPeriodMs, drainCapMs, value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CycleConfig)) return false;
        CycleConfig that = (CycleConfig) o;
        return clientPeriodMs == that.clientPeriodMs && transportPeriodMs == that.transportPeriodMs &&
                drainCapMs == that.drainCapMs && returnAfterVisit == that.returnAfterVisit;
    }

    @Override
    public int hashCode() {
        int h = Long.hashCode(clientPeriodMs);
        h = 31 * h + Long.hashCode(transportPeriodMs);
        h = 31 * h + Long.hashCode(drainCapMs);
        return 31 * h + Boolean.hashCode(returnAfterVisit);
    }

    @Override
    public String toString() {
        return "CycleConfig{client=" + clientPeriodMs + "ms, transport=" + transportPeriodMs + "ms, drainCap=" +
                drainCapMs + "ms, returnAfterVisit=" + returnAfterVisit + "}";
    }
}
