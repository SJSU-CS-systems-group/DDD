package net.discdd.bundledropbox.visit;

import net.discdd.bundledropbox.store.BundleRecord;
import net.discdd.bundledropbox.store.Direction;
import net.discdd.bundledropbox.store.RelayStore;
import net.discdd.bundledropbox.store.Staged;
import net.discdd.bundledropbox.store.StoreStats;
import net.discdd.utils.BundleUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Small file-backed {@link RelayStore} for visit tests, independent of the real {@code store/FileRelayStore}
 * (written in parallel by another module). It honours the contract points the visit relies on: staging is
 * invisible, commit takes the id from the jar's {@code bundle.id} when no id is claimed, and committed file name
 * equals the id.
 */
final class FakeRelayStore implements RelayStore {
    private final Path root;
    private final AtomicLong stagingCounter = new AtomicLong();
    private final AtomicLong commitClock = new AtomicLong(1_000);
    private final Map<Direction, Map<String, BundleRecord>> records = new LinkedHashMap<>();
    volatile boolean hasCapacity = true;
    int aborts;

    FakeRelayStore(Path root) throws IOException {
        this.root = root;
        for (Direction d : Direction.values()) {
            Files.createDirectories(dir(d));
            records.put(d, new LinkedHashMap<>());
        }
        Files.createDirectories(root.resolve("staging"));
    }

    private Path dir(Direction d) { return root.resolve(d.name().toLowerCase()); }

    /** Copies an existing jar into the store under direction {@code d}. */
    synchronized BundleRecord seed(Direction d, Path jar) throws IOException {
        Staged s = beginStaging(d);
        try (OutputStream out = s.open()) {
            Files.copy(jar, out);
        }
        return commit(s, null);
    }

    @Override
    public Staged beginStaging(Direction d) throws IOException {
        Path p = root.resolve("staging").resolve("st-" + stagingCounter.incrementAndGet() + ".tmp");
        Files.createFile(p);
        return new FakeStaged(p, d);
    }

    @Override
    public synchronized BundleRecord commit(Staged s, String claimedIdOrNull) throws IOException {
        FakeStaged staged = (FakeStaged) s;
        try {
            String jarId = readId(staged.path);
            String id = claimedIdOrNull != null ? claimedIdOrNull : jarId;
            if (id == null) throw new IOException("no bundle id");
            if (claimedIdOrNull != null && jarId != null && !claimedIdOrNull.equals(jarId)) {
                throw new IOException("id mismatch");
            }
            try {
                BundleUtils.checkIdClean(id);
            } catch (RuntimeException e) {
                throw new IOException("unclean id " + id);
            }
            Path target = dir(staged.direction).resolve(id);
            Files.move(staged.path, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            BundleRecord r = new BundleRecord(id, staged.direction, Files.size(target), commitClock.incrementAndGet());
            records.get(staged.direction).put(id, r);
            return r;
        } catch (IOException e) {
            Files.deleteIfExists(staged.path);
            throw e;
        }
    }

    private static String readId(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry("bundle.id");
            if (e == null) return null;
            try (InputStream in = z.getInputStream(e)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public synchronized void abort(Staged s) {
        aborts++;
        try {
            Files.deleteIfExists(s.path());
        } catch (IOException ignored) {
        }
    }

    @Override
    public synchronized List<BundleRecord> list(Direction d) {
        List<BundleRecord> l = new ArrayList<>(records.get(d).values());
        l.sort(Comparator.comparingLong(BundleRecord::committedAtMillis));
        return l;
    }

    @Override
    public synchronized Path pathFor(Direction d, String bundleId) {
        return records.get(d).containsKey(bundleId) ? dir(d).resolve(bundleId) : null;
    }

    @Override
    public synchronized boolean contains(Direction d, String bundleId) {
        return records.get(d).containsKey(bundleId);
    }

    @Override
    public synchronized StoreStats stats() {
        long up = list(Direction.UPSTREAM).stream().mapToLong(BundleRecord::sizeBytes).sum();
        long down = list(Direction.DOWNSTREAM).stream().mapToLong(BundleRecord::sizeBytes).sum();
        return new StoreStats(records.get(Direction.UPSTREAM).size(), up, records.get(Direction.DOWNSTREAM).size(),
                              down, 0, Long.MAX_VALUE);
    }

    @Override
    public boolean hasCapacityFor(long bytes) { return hasCapacity; }

    @Override
    public synchronized void delete(Direction d, String bundleId) {
        records.get(d).remove(bundleId);
        try {
            Files.deleteIfExists(dir(d).resolve(bundleId));
        } catch (IOException ignored) {
        }
    }

    /** @return number of files currently in staging (should be 0 after every visit). */
    long stagingFileCount() throws IOException {
        try (var s = Files.list(root.resolve("staging"))) {
            return s.count();
        }
    }

    private static final class FakeStaged implements Staged {
        final Path path;
        final Direction direction;

        FakeStaged(Path path, Direction direction) {
            this.path = path;
            this.direction = direction;
        }

        @Override
        public Path path() { return path; }

        @Override
        public OutputStream open() throws IOException { return Files.newOutputStream(path); }
    }
}
