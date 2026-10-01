package net.discdd.bundledropbox.store;

import java.util.Objects;

/**
 * Immutable description of one committed bundle in the {@link RelayStore}.
 *
 * <p>Plain final class rather than a Java record (project rule). Accessors are record-style
 * ({@code bundleId()}, not {@code getBundleId()}).
 */
public final class BundleRecord {
    private final String bundleId;
    private final Direction direction;
    private final long sizeBytes;
    private final long committedAtMillis;

    /**
     * @param bundleId          encrypted bundle id, already validated with {@code BundleUtils.checkIdClean}
     * @param direction         which queue the bundle lives in
     * @param sizeBytes         size of the committed file in bytes
     * @param committedAtMillis wall-clock time ({@code System.currentTimeMillis()}) of the commit; used
     *                          only for ordering and display, survives reboots via the file mtime
     */
    public BundleRecord(String bundleId, Direction direction, long sizeBytes, long committedAtMillis) {
        this.bundleId = Objects.requireNonNull(bundleId, "bundleId");
        this.direction = Objects.requireNonNull(direction, "direction");
        this.sizeBytes = sizeBytes;
        this.committedAtMillis = committedAtMillis;
    }

    /** @return the encrypted bundle id, which is also the file name on disk. */
    public String bundleId() { return bundleId; }

    /** @return the queue this bundle belongs to. */
    public Direction direction() { return direction; }

    /** @return size of the bundle file in bytes. */
    public long sizeBytes() { return sizeBytes; }

    /** @return wall-clock commit time in epoch millis. */
    public long committedAtMillis() { return committedAtMillis; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BundleRecord)) return false;
        BundleRecord that = (BundleRecord) o;
        return sizeBytes == that.sizeBytes && committedAtMillis == that.committedAtMillis &&
                bundleId.equals(that.bundleId) && direction == that.direction;
    }

    @Override
    public int hashCode() {
        return Objects.hash(bundleId, direction, sizeBytes, committedAtMillis);
    }

    @Override
    public String toString() {
        return "BundleRecord{" + direction + " " + bundleId + ", " + sizeBytes + " B, at " + committedAtMillis + "}";
    }
}
