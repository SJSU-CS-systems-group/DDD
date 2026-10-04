package net.discdd.bundledropbox.cycle;

/**
 * Completion callback for one {@link RadioDriver} operation.
 *
 * <p>Exactly one of {@link #ready(long)} or {@link #failed(long, String)} is called per operation. It
 * may be called on any thread (Wi-Fi P2P callbacks arrive on the main looper); the controller re-posts
 * to its own single-thread executor. Every call carries the generation number the operation was started
 * with, so the controller can ignore callbacks from an operation it has already abandoned (stale
 * generation).
 */
public interface RadioCallback {
    /**
     * The operation completed and the mode is actually usable (e.g. group formed and service added for
     * {@code startHosting}; group removal observed for {@code stopHosting}).
     *
     * @param gen generation passed to the {@link RadioDriver} call
     */
    void ready(long gen);

    /**
     * The operation failed. The controller retries with bounded backoff (3 attempts), then enters
     * {@link CycleMode#UNAVAILABLE}; a stop issued by a stop/pause/shutdown is tried once and then left
     * for the next start.
     *
     * @param gen    generation passed to the {@link RadioDriver} call
     * @param reason human-readable cause for logs and {@code DropboxStatus.lastError}
     */
    void failed(long gen, String reason);
}
