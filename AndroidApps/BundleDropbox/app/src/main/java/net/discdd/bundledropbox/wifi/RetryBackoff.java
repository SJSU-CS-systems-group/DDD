package net.discdd.bundledropbox.wifi;

/**
 * Exponential backoff for retrying a {@code WifiP2pManager} request that failed with {@code BUSY}.
 *
 * <p>The P2P framework answers BUSY while it is still processing a previous request (very common right
 * after removeGroup/createGroup or while discovery is running). That is transient, so the driver retries
 * BUSY a few times inside the operation's own timeout; every other failure code is reported to the
 * controller, which applies its own 3-attempt backoff (03 Architecture, "Failures").
 *
 * <p>Pure Java, immutable, unit-tested.
 */
public final class RetryBackoff {
    /** Default policy for BUSY: 250 ms, 500 ms, 1 s, 2 s, 2 s, 2 s (6 retries, about 8 s in total). */
    public static final RetryBackoff BUSY = new RetryBackoff(250L, 2_000L, 6);

    private final long initialDelayMs;
    private final long maxDelayMs;
    private final int maxRetries;

    /**
     * @param initialDelayMs delay before the first retry, &gt; 0
     * @param maxDelayMs     cap for any single delay, &gt;= initialDelayMs
     * @param maxRetries     number of retries allowed, &gt;= 0
     */
    public RetryBackoff(long initialDelayMs, long maxDelayMs, int maxRetries) {
        if (initialDelayMs <= 0) throw new IllegalArgumentException("initialDelayMs must be > 0");
        if (maxDelayMs < initialDelayMs) throw new IllegalArgumentException("maxDelayMs < initialDelayMs");
        if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must be >= 0");
        this.initialDelayMs = initialDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.maxRetries = maxRetries;
    }

    /**
     * @param retryIndex 0 for the first retry, 1 for the second, ...
     * @return delay in ms before that retry, or -1 if no more retries are allowed
     */
    public long delayBeforeRetry(int retryIndex) {
        if (retryIndex < 0 || retryIndex >= maxRetries) return -1L;
        long delay = initialDelayMs;
        for (int i = 0; i < retryIndex && delay < maxDelayMs; i++) delay *= 2;
        return Math.min(delay, maxDelayMs);
    }

    /** @return number of retries allowed */
    public int maxRetries() { return maxRetries; }
}
