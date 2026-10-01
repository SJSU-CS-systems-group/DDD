package net.discdd.bundledropbox.store;

/**
 * Immutable snapshot of {@link RelayStore} occupancy, used by the Storage tab, the status
 * repository, and capacity checks before admitting new bundles.
 *
 * <p>Only committed bundles are counted per direction; bytes still being received are reported
 * separately as {@link #stagedBytes()}.
 */
public final class StoreStats {
    private final int upstreamCount;
    private final long upstreamBytes;
    private final int downstreamCount;
    private final long downstreamBytes;
    private final long stagedBytes;
    private final long capacityBytes;

    /**
     * @param upstreamCount   number of committed client-to-server bundles
     * @param upstreamBytes   total bytes of committed client-to-server bundles
     * @param downstreamCount number of committed server-to-client bundles
     * @param downstreamBytes total bytes of committed server-to-client bundles
     * @param stagedBytes     bytes currently sitting in {@code staging/} (in-flight receives)
     * @param capacityBytes   configured store capacity ({@code max_storage_mb} setting, in bytes)
     */
    public StoreStats(int upstreamCount, long upstreamBytes, int downstreamCount, long downstreamBytes,
                      long stagedBytes, long capacityBytes) {
        this.upstreamCount = upstreamCount;
        this.upstreamBytes = upstreamBytes;
        this.downstreamCount = downstreamCount;
        this.downstreamBytes = downstreamBytes;
        this.stagedBytes = stagedBytes;
        this.capacityBytes = capacityBytes;
    }

    /** @return number of committed {@link Direction#UPSTREAM} bundles. */
    public int upstreamCount() { return upstreamCount; }

    /** @return total size of committed {@link Direction#UPSTREAM} bundles. */
    public long upstreamBytes() { return upstreamBytes; }

    /** @return number of committed {@link Direction#DOWNSTREAM} bundles. */
    public int downstreamCount() { return downstreamCount; }

    /** @return total size of committed {@link Direction#DOWNSTREAM} bundles. */
    public long downstreamBytes() { return downstreamBytes; }

    /** @return bytes in partially received (staged, uncommitted) files. */
    public long stagedBytes() { return stagedBytes; }

    /** @return configured capacity in bytes. */
    public long capacityBytes() { return capacityBytes; }

    /** @return committed count for the given direction. */
    public int count(Direction d) { return d == Direction.UPSTREAM ? upstreamCount : downstreamCount; }

    /** @return committed bytes for the given direction. */
    public long bytes(Direction d) { return d == Direction.UPSTREAM ? upstreamBytes : downstreamBytes; }

    /** @return all bytes the store currently occupies (committed in both directions plus staged). */
    public long usedBytes() { return upstreamBytes + downstreamBytes + stagedBytes; }

    /** @return bytes left before reaching capacity, never negative. */
    public long freeBytes() { return Math.max(0, capacityBytes - usedBytes()); }

    @Override
    public String toString() {
        return "StoreStats{up=" + upstreamCount + "/" + upstreamBytes + "B, down=" + downstreamCount + "/" +
                downstreamBytes + "B, staged=" + stagedBytes + "B, capacity=" + capacityBytes + "B}";
    }
}
