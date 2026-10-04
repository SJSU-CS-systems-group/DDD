package net.discdd.bundledropbox.store;

import java.util.Objects;

/**
 * Immutable description of one committed bundle in the {@link RelayStore}.
 *
 * <p>Besides the id, direction and size, a record carries three pieces of metadata the HOST side needs
 * to decide which DOWNSTREAM bundle to offer a client (Audit M7/M9):
 * <ul>
 *   <li>{@link #clientTag()}: which client the bundle belongs to, taken from the jar's plaintext
 *       {@code clientBase.pub} entry. Every bundle in both directions carries it unencrypted, so the
 *       dropbox can group bundles per client without decrypting anything (Research/R8 §5).</li>
 *   <li>{@link #builtAtMillis()}: when the jar was built (the zip timestamp of its {@code bundle.id}
 *       entry). For DOWNSTREAM that is the server's bundle-generation time, which orders two versions
 *       of one client's bundle even when they reached the dropbox out of order via different carriers.</li>
 *   <li>{@link #lastSeenAtMillis()}: the last time a carrier offered this id again ({@link RelayStore#touch}),
 *       i.e. the server still considers it current.</li>
 * </ul>
 *
 * <p>Plain final class rather than a Java record (project rule). Accessors are record-style
 * ({@code bundleId()}, not {@code getBundleId()}).
 */
public final class BundleRecord {
    /** Value of {@link #builtAtMillis()} when the jar carries no usable timestamp. */
    public static final long UNKNOWN_TIME = -1L;

    private final String bundleId;
    private final Direction direction;
    private final long sizeBytes;
    private final long committedAtMillis;
    private final long lastSeenAtMillis;
    private final String clientTag;
    private final long builtAtMillis;

    /**
     * Record without jar metadata (tests and callers that only need id, size and order).
     *
     * @param bundleId          encrypted bundle id, already validated with {@code BundleUtils.checkIdClean}
     * @param direction         which queue the bundle lives in
     * @param sizeBytes         size of the committed file in bytes
     * @param committedAtMillis wall-clock time ({@code System.currentTimeMillis()}) of the commit; also used
     *                          as {@link #lastSeenAtMillis()}
     */
    public BundleRecord(String bundleId, Direction direction, long sizeBytes, long committedAtMillis) {
        this(bundleId, direction, sizeBytes, committedAtMillis, committedAtMillis, null, UNKNOWN_TIME);
    }

    /**
     * Full constructor.
     *
     * @param bundleId          encrypted bundle id, already validated with {@code BundleUtils.checkIdClean}
     * @param direction         which queue the bundle lives in
     * @param sizeBytes         size of the committed file in bytes
     * @param committedAtMillis wall-clock time of the commit (see {@link #committedAtMillis()})
     * @param lastSeenAtMillis  wall-clock time a carrier last offered this id (at least the commit time)
     * @param clientTag         per-client tag from the jar's {@code clientBase.pub}, or {@code null} if absent
     * @param builtAtMillis     jar build time from the {@code bundle.id} entry, or {@link #UNKNOWN_TIME}
     */
    public BundleRecord(String bundleId, Direction direction, long sizeBytes, long committedAtMillis,
                        long lastSeenAtMillis, String clientTag, long builtAtMillis) {
        this.bundleId = Objects.requireNonNull(bundleId, "bundleId");
        this.direction = Objects.requireNonNull(direction, "direction");
        this.sizeBytes = sizeBytes;
        this.committedAtMillis = committedAtMillis;
        this.lastSeenAtMillis = Math.max(lastSeenAtMillis, committedAtMillis);
        this.clientTag = clientTag;
        this.builtAtMillis = builtAtMillis;
    }

    /** @return the encrypted bundle id, which is also the file name on disk. */
    public String bundleId() { return bundleId; }

    /** @return the queue this bundle belongs to. */
    public Direction direction() { return direction; }

    /** @return size of the bundle file in bytes. */
    public long sizeBytes() { return sizeBytes; }

    /**
     * @return wall-clock commit time in epoch millis. Persisted as the file mtime, which {@link RelayStore#touch}
     *         also advances, so after a restart a touched DOWNSTREAM bundle reads back its last-seen time here.
     *         UPSTREAM bundles are never touched, so their value is the original commit time.
     */
    public long committedAtMillis() { return committedAtMillis; }

    /** @return the last time a carrier offered this id (or the commit time if never re-offered). */
    public long lastSeenAtMillis() { return lastSeenAtMillis; }

    /**
     * @return {@code base64url(SHA-256(clientBase.pub key bytes))} identifying the bundle's client, or
     *         {@code null} if the jar has no {@code clientBase.pub} entry (not a real DDD bundle)
     */
    public String clientTag() { return clientTag; }

    /** @return when the jar was built (epoch millis, 2 s resolution), or {@link #UNKNOWN_TIME}. */
    public long builtAtMillis() { return builtAtMillis; }

    /**
     * @param atMillis when a carrier re-offered this id
     * @return a copy with {@link #lastSeenAtMillis()} advanced to {@code atMillis} (never moved backwards)
     */
    public BundleRecord withLastSeen(long atMillis) {
        if (atMillis <= lastSeenAtMillis) return this;
        return new BundleRecord(bundleId, direction, sizeBytes, committedAtMillis, atMillis, clientTag, builtAtMillis);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BundleRecord)) return false;
        BundleRecord that = (BundleRecord) o;
        return sizeBytes == that.sizeBytes && committedAtMillis == that.committedAtMillis &&
                lastSeenAtMillis == that.lastSeenAtMillis && builtAtMillis == that.builtAtMillis &&
                bundleId.equals(that.bundleId) && direction == that.direction &&
                Objects.equals(clientTag, that.clientTag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bundleId, direction, sizeBytes, committedAtMillis, lastSeenAtMillis, clientTag,
                            builtAtMillis);
    }

    @Override
    public String toString() {
        return "BundleRecord{" + direction + " " + bundleId + ", " + sizeBytes + " B, at " + committedAtMillis +
                (lastSeenAtMillis != committedAtMillis ? ", seen " + lastSeenAtMillis : "") +
                (clientTag != null ? ", client " + clientTag.substring(0, Math.min(8, clientTag.length())) : "") +
                "}";
    }
}
