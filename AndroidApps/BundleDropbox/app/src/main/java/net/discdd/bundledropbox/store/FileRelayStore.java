package net.discdd.bundledropbox.store;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Filesystem implementation of {@link RelayStore}: the dropbox's two opaque bundle queues on disk.
 *
 * <p>Layout comes from {@link DropboxPaths}: committed bundles live in {@code Dropbox/upstream/} and
 * {@code Dropbox/downstream/}, named by their encrypted bundle id; in-flight receives live in
 * {@code Dropbox/staging/} under random temp names.
 *
 * <p>Lifecycle of a received bundle:
 * <ol>
 *   <li>{@link #beginStaging} creates {@code staging/<dir>-<random>.part}. The writer (gRPC upload
 *       observer in HOST mode, PSI download loop in VISIT mode) streams into {@link Staged#open()}.</li>
 *   <li>{@link #commit} closes any still-open stream, resolves the id (claimed and/or jar
 *       {@code bundle.id}), validates it, fsyncs, and {@code ATOMIC_MOVE}s the file to
 *       {@code <direction>/<id>}, replacing an existing same-id bundle.</li>
 *   <li>{@link #abort} closes the stream and deletes the temp file.</li>
 * </ol>
 * Because only step 2 makes a file visible, PSI never hashes (and the visit never forwards) a partial
 * file. The stock transport writes uploads straight to their final name, which is exactly the
 * truncated-file problem this avoids (Research/R2 §3, R3 §7).
 *
 * <p><b>Index.</b> Committed bundles are tracked in an in-memory index rebuilt from disk by
 * {@link #reconcile()} (called by the constructor), so {@link #list}, {@link #contains} and
 * {@link #stats} never hit the disk. Files in the queue directories whose names are not clean bundle
 * ids (e.g. stray {@code .tmp} files, {@code recencyBlob.bin}) are ignored, never listed or served.
 * The store assumes it is the only writer of these directories.
 *
 * <p><b>Threading.</b> Thread-safe. Index updates and renames happen under one lock; receives stream
 * into their own staging files without holding it, so concurrent client uploads and a visit download
 * never block each other except for the instant of the rename.
 *
 * <p><b>Capacity.</b> {@link #hasCapacityFor} compares committed + staged bytes against a capacity the
 * service sets from the {@code max_storage_mb} preference ({@link #setCapacityBytes}). DOWNSTREAM is further
 * limited to {@code capacity - upstreamReserve} ({@link #setUpstreamReservePercent}, default
 * {@value #DEFAULT_UPSTREAM_RESERVE_PERCENT}%), so download-all can never take the space client uploads
 * need (Audit M10); UPSTREAM may use any space DOWNSTREAM leaves free. The store itself never refuses a
 * commit for capacity: the bytes are already on disk and counted as staged by then.
 *
 * <p><b>Jar metadata.</b> On commit and on {@link #reconcile()} the store reads each bundle's plaintext
 * {@code clientBase.pub} tag and build time ({@link BundleIds#inspect}) into its {@link BundleRecord}, so
 * inventory reports can identify client keys without reopening each jar. HOST offers all retained versions.
 *
 * <p>Pure Java, no Android imports, so it runs in JVM unit tests.
 */
public final class FileRelayStore implements RelayStore {
    private static final Logger logger = Logger.getLogger(FileRelayStore.class.getName());

    /** Suffix of staging temp files. Contains '.', so it can never be mistaken for a clean bundle id. */
    static final String STAGING_SUFFIX = ".part";

    /** Default share of the capacity DOWNSTREAM may never use, kept free for client uploads. */
    public static final int DEFAULT_UPSTREAM_RESERVE_PERCENT = 25;

    private final DropboxPaths paths;
    private final LongSupplier wallClockMillis;
    private volatile long capacityBytes;
    private volatile int upstreamReservePercent = DEFAULT_UPSTREAM_RESERVE_PERCENT;

    /** Guards {@link #index} and every rename/delete inside the queue directories. */
    private final Object lock = new Object();
    /** Committed bundles per direction, keyed by bundle id. Guarded by {@link #lock}. */
    private final Map<Direction, Map<String, BundleRecord>> index = new EnumMap<>(Direction.class);
    /** Staged handles that are neither committed nor aborted yet; used for staged-byte accounting. */
    private final Set<StagedFile> activeStaged = ConcurrentHashMap.newKeySet();

    /**
     * Creates the directories, deletes leftover staging files from a previous run and indexes the
     * committed bundles. Uses {@code System.currentTimeMillis()} for commit timestamps.
     *
     * @param paths         dropbox layout
     * @param capacityBytes initial capacity (see {@code DropboxPrefs.maxStorageBytes})
     * @throws IOException if the directories cannot be created or scanned
     */
    public FileRelayStore(DropboxPaths paths, long capacityBytes) throws IOException {
        this(paths, capacityBytes, System::currentTimeMillis);
    }

    /**
     * Same as {@link #FileRelayStore(DropboxPaths, long)} with an injectable wall clock (tests).
     *
     * @param wallClockMillis source of {@link BundleRecord#committedAtMillis()}; also written as the
     *                        file mtime so ordering survives restarts
     */
    public FileRelayStore(DropboxPaths paths, long capacityBytes, LongSupplier wallClockMillis) throws IOException {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.wallClockMillis = Objects.requireNonNull(wallClockMillis, "wallClockMillis");
        setCapacityBytes(capacityBytes);
        for (Direction d : Direction.values()) index.put(d, new HashMap<>());
        paths.createDirectories();
        reconcile();
    }

    /**
     * Updates the capacity used by {@link #hasCapacityFor} and reported in {@link #stats}. Call when the
     * {@code max_storage_mb} setting changes. Lowering it below current usage deletes nothing.
     *
     * @param capacityBytes new capacity, must be positive
     */
    public void setCapacityBytes(long capacityBytes) {
        if (capacityBytes <= 0) throw new IllegalArgumentException("capacityBytes must be > 0: " + capacityBytes);
        this.capacityBytes = capacityBytes;
    }

    /**
     * Sets the share of the capacity reserved for UPSTREAM (client uploads). DOWNSTREAM receives are refused
     * once DOWNSTREAM committed + staged bytes would exceed {@code capacity * (100 - percent) / 100}.
     *
     * @param percent 0 (no reserve, the v1 shared budget) to 90
     */
    public void setUpstreamReservePercent(int percent) {
        if (percent < 0 || percent > 90) throw new IllegalArgumentException("reserve percent must be 0..90: " + percent);
        this.upstreamReservePercent = percent;
    }

    /** @return the most bytes DOWNSTREAM (committed + staged) may occupy. */
    public long downstreamQuotaBytes() {
        long cap = capacityBytes;
        return cap - cap / 100 * upstreamReservePercent - (cap % 100) * upstreamReservePercent / 100;
    }

    /**
     * Startup recovery: deletes staging files not owned by a live {@link Staged} handle (receives that
     * were interrupted by a crash or kill; the peer re-sends them on its next exchange) and rebuilds the
     * committed index from the queue directories.
     *
     * <p>Called by the constructor. Safe to call again later (e.g. after an operator wipes storage).
     *
     * @return number of leftover staging files deleted
     * @throws IOException if a directory cannot be listed
     */
    public int reconcile() throws IOException {
        int removed = 0;
        Set<Path> live = new java.util.HashSet<>();
        for (StagedFile s : activeStaged) live.add(s.path);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(paths.staging)) {
            for (Path p : ds) {
                if (live.contains(p)) continue;
                AtomicFiles.deleteQuietly(p);
                removed++;
            }
        }
        if (removed > 0) logger.log(Level.INFO, "Removed " + removed + " leftover staging file(s)");

        synchronized (lock) {
            for (Direction d : Direction.values()) {
                Map<String, BundleRecord> m = index.get(d);
                m.clear();
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(paths.dirFor(d))) {
                    for (Path p : ds) {
                        String name = p.getFileName().toString();
                        if (!Files.isRegularFile(p) || !BundleIds.isClean(name)) {
                            logger.log(Level.FINE, "Ignoring non-bundle file " + p);
                            continue;
                        }
                        long mtime = Files.getLastModifiedTime(p).toMillis();
                        BundleIds.JarInfo info = BundleIds.inspect(p).orElse(null);
                        m.put(name, new BundleRecord(name, d, Files.size(p), mtime, mtime,
                                                     info == null ? null : info.clientTag(),
                                                     info == null ? BundleRecord.UNKNOWN_TIME : info.builtAtMillis()));
                    }
                }
            }
        }
        return removed;
    }

    @Override
    public Staged beginStaging(Direction d) throws IOException {
        Objects.requireNonNull(d, "direction");
        Files.createDirectories(paths.staging);
        Path tmp = Files.createTempFile(paths.staging, d.name().toLowerCase(java.util.Locale.ROOT) + "-", STAGING_SUFFIX);
        StagedFile s = new StagedFile(this, d, tmp);
        activeStaged.add(s);
        return s;
    }

    @Override
    public BundleRecord commit(Staged staged, String claimedIdOrNull) throws IOException {
        StagedFile s = own(staged);
        if (!s.beginCommit()) {
            throw new IOException("Staged bundle already committed or aborted: " + s.path.getFileName());
        }
        try {
            // "commit = flush/close": the writer may still hold the stream (e.g. a gRPC observer that
            // completes and commits in one step). Closing flushes it; closing twice is harmless.
            s.closeStream();
            if (!Files.isRegularFile(s.path)) throw new IOException("Staged file vanished: " + s.path);

            BundleIds.JarInfo info = BundleIds.inspect(s.path).orElse(null);
            String id = resolveId(info == null ? null : info.rawId(), claimedIdOrNull);
            Path target = targetFor(s.direction, id);
            long size = Files.size(s.path);
            long now = wallClockMillis.getAsLong();
            // mtime carries committedAt across restarts (reconcile reads it back).
            Files.setLastModifiedTime(s.path, FileTime.fromMillis(now));
            AtomicFiles.fsync(s.path);

            BundleRecord rec = new BundleRecord(id, s.direction, size, now, now,
                                                info == null ? null : info.clientTag(),
                                                info == null ? BundleRecord.UNKNOWN_TIME : info.builtAtMillis());
            synchronized (lock) {
                // Same id replaces: clients re-send the same bundle id until acked and the server re-offers
                // the same downstream id until superseded (Research/R4 §4.2), so overwrite is the dedupe.
                // moveReplacing also fsyncs the queue directory, so an acknowledged commit survives power loss.
                AtomicFiles.moveReplacing(s.path, target);
                BundleRecord old = index.get(s.direction).put(id, rec);
                if (old != null) logger.log(Level.FINE, "Replaced " + s.direction + " bundle " + id);
            }
            return rec;
        } catch (IOException | RuntimeException e) {
            AtomicFiles.deleteQuietly(s.path);
            throw e;
        } finally {
            s.finish();
            activeStaged.remove(s);
        }
    }

    @Override
    public void abort(Staged staged) {
        if (!(staged instanceof StagedFile) || ((StagedFile) staged).store != this) {
            logger.log(Level.WARNING, "abort() ignored: foreign staged handle " + staged);
            return;
        }
        StagedFile s = (StagedFile) staged;
        // Aborting during an in-progress commit is a no-op: the commit either lands or cleans up itself.
        if (!s.beginAbort()) return;
        try {
            // Closing under a concurrent writer makes its next write fail, which is the point: the
            // transfer is being cancelled (e.g. drain cap reached) and the peer will retry later.
            s.closeStream();
        } catch (IOException e) {
            logger.log(Level.FINE, "Ignoring close failure while aborting " + s, e);
        } finally {
            AtomicFiles.deleteQuietly(s.path);
            s.finish();
            activeStaged.remove(s);
        }
    }

    /** {@inheritDoc} Ordering basis: committedAt (file mtime across restarts), then bundle id. */
    @Override
    public List<BundleRecord> list(Direction d) {
        List<BundleRecord> out;
        synchronized (lock) {
            out = new ArrayList<>(index.get(d).values());
        }
        out.sort(Comparator.comparingLong(BundleRecord::committedAtMillis).thenComparing(BundleRecord::bundleId));
        return out;
    }

    @Override
    public Path pathFor(Direction d, String bundleId) {
        if (!BundleIds.isClean(bundleId)) return null;
        synchronized (lock) {
            return index.get(d).containsKey(bundleId) ? paths.dirFor(d).resolve(bundleId) : null;
        }
    }

    @Override
    public boolean contains(Direction d, String bundleId) {
        if (bundleId == null) return false;
        synchronized (lock) {
            return index.get(d).containsKey(bundleId);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Also advances the file mtime so the last-seen time survives a restart (reconcile reads it back as
     * both committedAt and lastSeen). A failure to set the mtime only loses that persistence and is logged.
     */
    @Override
    public boolean touch(Direction d, String bundleId) {
        if (!BundleIds.isClean(bundleId)) return false;
        long now = wallClockMillis.getAsLong();
        synchronized (lock) {
            BundleRecord r = index.get(d).get(bundleId);
            if (r == null) return false;
            BundleRecord touched = r.withLastSeen(now);
            if (touched == r) return true;
            try {
                Files.setLastModifiedTime(paths.dirFor(d).resolve(bundleId), FileTime.fromMillis(now));
            } catch (IOException e) {
                logger.log(Level.FINE, "Could not persist last-seen time of " + d + " bundle " + bundleId, e);
            }
            index.get(d).put(bundleId, touched);
            return true;
        }
    }

    @Override
    public StoreStats stats() {
        int upCount, downCount;
        long upBytes, downBytes;
        synchronized (lock) {
            Map<String, BundleRecord> up = index.get(Direction.UPSTREAM);
            Map<String, BundleRecord> down = index.get(Direction.DOWNSTREAM);
            upCount = up.size();
            downCount = down.size();
            upBytes = sum(up);
            downBytes = sum(down);
        }
        return new StoreStats(upCount, upBytes, downCount, downBytes, stagedBytes(null), capacityBytes);
    }

    @Override
    public boolean hasCapacityFor(Direction d, long bytes) {
        Objects.requireNonNull(d, "direction");
        long upBytes, downBytes;
        synchronized (lock) {
            upBytes = sum(index.get(Direction.UPSTREAM));
            downBytes = sum(index.get(Direction.DOWNSTREAM));
        }
        long stagedDown = stagedBytes(Direction.DOWNSTREAM);
        long stagedUp = stagedBytes(Direction.UPSTREAM);
        long incoming = Math.max(0, bytes);
        if (upBytes + downBytes + stagedUp + stagedDown + incoming > capacityBytes) return false;
        return d == Direction.UPSTREAM || downBytes + stagedDown + incoming <= downstreamQuotaBytes();
    }

    /** {@inheritDoc} */
    @Override
    public void delete(Direction d, String bundleId) {
        if (!BundleIds.isClean(bundleId)) return;
        synchronized (lock) {
            Path p = paths.dirFor(d).resolve(bundleId);
            try {
                Files.deleteIfExists(p);
                index.get(d).remove(bundleId);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not delete " + d + " bundle " + bundleId, e);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Picks the bundle id for a staged file.
     *
     * <p>Sources: the peer's claim (client {@code BundleUploadRequest.bundleId}) and the jar's
     * {@code bundle.id} (the only source in VISIT download-all, where {@code psiDownloadBundle} carries
     * no name, Research/R2 §4.1). If both exist they must agree; the jar entry is authoritative for what
     * the stock client blinds in PSI, so a mismatch means the file would never be matched and is
     * rejected. A claim with no readable jar id is accepted as-is, like the stock transport does.
     */
    private static String resolveId(String jarIdOrNull, String claimedIdOrNull) throws IOException {
        String claimed = claimedIdOrNull == null ? null : claimedIdOrNull.trim();
        if (claimed != null && claimed.isEmpty()) claimed = null;
        Optional<String> fromJar = Optional.ofNullable(jarIdOrNull);
        String id;
        if (claimed != null && fromJar.isPresent()) {
            if (!claimed.equals(fromJar.get())) {
                throw new IOException("Claimed bundle id does not match jar " + BundleIds.BUNDLE_ID_ENTRY);
            }
            id = claimed;
        } else if (claimed != null) {
            id = claimed;
        } else if (fromJar.isPresent()) {
            id = fromJar.get();
        } else {
            throw new IOException("No bundle id: nothing claimed and no " + BundleIds.BUNDLE_ID_ENTRY + " in jar");
        }
        return BundleIds.requireClean(id);
    }

    /** Resolves {@code <dir>/<id>} and double-checks it cannot escape the directory. */
    private Path targetFor(Direction d, String id) throws IOException {
        Path dir = paths.dirFor(d);
        Path target = dir.resolve(id).normalize();
        if (!dir.normalize().equals(target.getParent()) || !target.getFileName().toString().equals(id)) {
            throw new IOException("Bundle id resolves outside its queue: " + id);
        }
        return target;
    }

    private StagedFile own(Staged staged) {
        if (!(staged instanceof StagedFile) || ((StagedFile) staged).store != this) {
            throw new IllegalArgumentException("Staged handle was not created by this store");
        }
        return (StagedFile) staged;
    }

    /** @param d direction to count, or null for both */
    private long stagedBytes(Direction d) {
        long total = 0;
        for (StagedFile s : activeStaged) {
            if (d != null && s.direction != d) continue;
            try {
                total += Files.size(s.path);
            } catch (IOException e) {
                // Being committed/aborted concurrently; it no longer counts as staged.
            }
        }
        return total;
    }

    private static long sum(Map<String, BundleRecord> m) {
        long t = 0;
        for (BundleRecord r : m.values()) t += r.sizeBytes();
        return t;
    }

    /**
     * {@link Staged} handle: the temp file, its target direction, the last opened stream and a tiny
     * state machine so commit/abort run exactly once even when called from different threads (e.g. the
     * cycle controller aborting at the drain cap while a gRPC thread finishes the stream).
     */
    private static final class StagedFile implements Staged {
        private static final int OPEN = 0, COMMITTING = 1, ABORTING = 2, DONE = 3;

        final FileRelayStore store;
        final Direction direction;
        final Path path;
        private int state = OPEN;
        private OutputStream stream;

        StagedFile(FileRelayStore store, Direction direction, Path path) {
            this.store = store;
            this.direction = direction;
            this.path = path;
        }

        @Override
        public Path path() { return path; }

        @Override
        public synchronized OutputStream open() throws IOException {
            if (state != OPEN) throw new IOException("Staged bundle is no longer open: " + path.getFileName());
            closeQuietly(stream);   // re-open truncates and restarts, per the Staged contract
            stream = Files.newOutputStream(path);   // CREATE, TRUNCATE_EXISTING, WRITE
            return stream;
        }

        synchronized boolean beginCommit() {
            if (state != OPEN) return false;
            state = COMMITTING;
            return true;
        }

        synchronized boolean beginAbort() {
            if (state != OPEN) return false;
            state = ABORTING;
            return true;
        }

        synchronized void finish() { state = DONE; }

        /** Closes (and thereby flushes) the writer's stream. */
        void closeStream() throws IOException {
            OutputStream s;
            synchronized (this) {
                s = stream;
                stream = null;
            }
            if (s != null) s.close();
        }

        private static void closeQuietly(OutputStream s) {
            if (s == null) return;
            try {
                s.close();
            } catch (IOException ignored) {
                // restarting the file anyway
            }
        }

        @Override
        public String toString() { return "Staged{" + direction + " " + path.getFileName() + "}"; }
    }
}
