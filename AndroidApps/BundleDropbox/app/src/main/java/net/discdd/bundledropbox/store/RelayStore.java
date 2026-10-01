package net.discdd.bundledropbox.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The dropbox's opaque bundle store: two queues ({@link Direction#UPSTREAM} and
 * {@link Direction#DOWNSTREAM}) of encrypted bundle files, plus a staging area for in-flight receives.
 *
 * <p>Who uses it:
 * <ul>
 *   <li>HOST mode ({@code rpc/DropboxRpcServer}): client uploads are staged then committed to UPSTREAM;
 *       PSI lists DOWNSTREAM file names and streams them back to clients.</li>
 *   <li>VISIT mode ({@code visit/TransportVisitClient}): UPSTREAM bundles are uploaded to the carrier;
 *       downloaded bundles are staged then committed to DOWNSTREAM.</li>
 *   <li>UI / status: {@link #stats()} and {@link #list(Direction)}.</li>
 * </ul>
 *
 * <p>Invariants every implementation must keep:
 * <ul>
 *   <li>Committed file name == encrypted bundle id, bytes unchanged. The stock client hashes the
 *       DOWNSTREAM file names in PSI, so any renaming breaks delivery (Research/R1 §3.3).</li>
 *   <li>Only committed files are ever listed, counted per direction, forwarded or served. Staged files
 *       live in {@link DropboxPaths#staging} and are invisible to PSI and to forwarding.</li>
 *   <li>Thread-safe: called concurrently from the gRPC executor, the visit worker and the UI.</li>
 *   <li>v1 never deletes on its own ({@code KeepAllRetentionPolicy}); see {@link #delete}.</li>
 * </ul>
 * Implemented by {@code store/FileRelayStore}.
 */
public interface RelayStore {

    /**
     * Starts receiving a bundle for direction {@code d} by creating a fresh, uniquely named temp file
     * in {@link DropboxPaths#staging}.
     *
     * @param d the queue the bundle will be committed to
     * @return a handle the caller writes into and then commits or aborts
     * @throws IOException if the staging file cannot be created
     */
    Staged beginStaging(Direction d) throws IOException;

    /**
     * Makes a fully written staged bundle visible in its queue.
     *
     * <p>The bundle id is taken from {@code claimedIdOrNull} (e.g. the {@code bundleId} a client sent
     * in {@code BundleUploadRequest}) and/or from the {@code bundle.id} entry inside the jar. If both
     * are available they must match. The id must pass {@code BundleUtils.checkIdClean}
     * ({@code ^[a-zA-Z0-9-_=]+$}, max 100 chars) because the stock transport never sanitizes upload ids
     * and the id becomes a file name (Research/R1 §3.2). The file is then moved atomically to
     * {@code <direction>/<bundleId>}, replacing any existing file with the same id (re-uploads of the
     * same bundle dedupe naturally).
     *
     * <p>On any failure the staged file is deleted and the exception is thrown; the store is unchanged.
     *
     * @param s               the staged handle from {@link #beginStaging(Direction)}; its stream must be closed
     * @param claimedIdOrNull the id the peer claims, or {@code null} to rely on the jar's {@code bundle.id}
     * @return the record of the committed bundle
     * @throws IOException if no id can be determined, the ids disagree, the id is unclean, or the move fails
     */
    BundleRecord commit(Staged s, String claimedIdOrNull) throws IOException;

    /**
     * Discards a staged bundle (cancelled transfer, drain cap reached, I/O error). Idempotent and
     * never throws; a missing file is ignored. The peer will simply re-send it on a later exchange.
     *
     * @param s the staged handle to discard
     */
    void abort(Staged s);

    /**
     * @param d the queue to list
     * @return committed bundles only, sorted by {@link BundleRecord#committedAtMillis()} ascending
     *         (oldest first); a snapshot, never {@code null}
     */
    List<BundleRecord> list(Direction d);

    /**
     * @param d        the queue to look in
     * @param bundleId encrypted bundle id
     * @return the committed file for that id, or {@code null} if absent (or the id is unclean)
     */
    Path pathFor(Direction d, String bundleId);

    /**
     * @param d        the queue to look in
     * @param bundleId encrypted bundle id
     * @return true if a committed bundle with that id exists in {@code d}. VISIT download-all uses this
     *         to skip bundles it already holds.
     */
    boolean contains(Direction d, String bundleId);

    /** @return a fresh occupancy snapshot (counts and bytes per direction, staged bytes, capacity). */
    StoreStats stats();

    /**
     * @param bytes size of a bundle about to be received (use 0 if unknown)
     * @return true if committed + staged bytes + {@code bytes} stay within the configured capacity
     */
    boolean hasCapacityFor(long bytes);

    /**
     * Removes a committed bundle. Missing ids are ignored.
     *
     * <p>ONLY for the operator "clear" action in the Storage tab. Nothing in the v1 relay path deletes
     * bundles: the dropbox cannot know whether a bundle was delivered end to end.
     * FUTURE: server keep/delete control ADUs (via {@link RetentionPolicy}) will be the other caller.
     *
     * @param d        the queue
     * @param bundleId encrypted bundle id
     */
    void delete(Direction d, String bundleId);
}
