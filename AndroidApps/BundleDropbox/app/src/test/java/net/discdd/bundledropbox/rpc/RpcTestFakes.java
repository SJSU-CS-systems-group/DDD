package net.discdd.bundledropbox.rpc;

import io.grpc.stub.StreamObserver;
import net.discdd.bundledropbox.cycle.Clock;
import net.discdd.bundledropbox.store.BundleRecord;
import net.discdd.bundledropbox.store.DeliveryLog;
import net.discdd.bundledropbox.store.Direction;
import net.discdd.bundledropbox.store.RecencyBlobStore;
import net.discdd.bundledropbox.store.RelayStore;
import net.discdd.bundledropbox.store.Staged;
import net.discdd.bundledropbox.store.StoreStats;
import net.discdd.utils.BundleUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Minimal, self-contained fakes for the rpc tests, so they do not depend on the store agent's
 * {@code FileRelayStore} implementation. They follow the {@link RelayStore} contract closely enough for
 * the rpc layer: staging dir, atomic move on commit, committed-only listing, simple byte capacity.
 */
final class RpcTestFakes {
    private RpcTestFakes() {}

    /** Settable monotonic clock. */
    static final class FakeClock implements Clock {
        final AtomicLong now = new AtomicLong(1_000);

        @Override
        public long nowMillis() {
            return now.get();
        }

        void advance(long ms) {
            now.addAndGet(ms);
        }
    }

    /** Directory-backed relay store; commit uses the claimed id only (no jar parsing). */
    static final class FakeRelayStore implements RelayStore {
        final Path root;
        final Path staging;
        final Map<Direction, Path> dirs = new LinkedHashMap<>();
        final Map<Direction, LinkedHashMap<String, BundleRecord>> committed = new LinkedHashMap<>();
        volatile long capacityBytes = Long.MAX_VALUE / 4;
        final List<Staged> aborted = new CopyOnWriteArrayList<>();

        FakeRelayStore(Path root) throws IOException {
            this.root = root;
            this.staging = Files.createDirectories(root.resolve("staging"));
            for (Direction d : Direction.values()) {
                dirs.put(d, Files.createDirectories(root.resolve(d.name().toLowerCase())));
                committed.put(d, new LinkedHashMap<>());
            }
        }

        synchronized void putDownstream(String id, byte[] bytes) throws IOException {
            Path p = dirs.get(Direction.DOWNSTREAM).resolve(id);
            Files.write(p, bytes);
            committed.get(Direction.DOWNSTREAM)
                    .put(id, new BundleRecord(id, Direction.DOWNSTREAM, bytes.length, System.currentTimeMillis()));
        }

        synchronized int stagedFileCount() throws IOException {
            try (Stream<Path> s = Files.list(staging)) {
                return (int) s.count();
            }
        }

        @Override
        public synchronized Staged beginStaging(Direction d) throws IOException {
            Path p = Files.createTempFile(staging, d.name(), ".part");
            return new Staged() {
                @Override
                public Path path() {
                    return p;
                }

                @Override
                public OutputStream open() throws IOException {
                    return Files.newOutputStream(p);
                }
            };
        }

        @Override
        public synchronized BundleRecord commit(Staged s, String claimedIdOrNull) throws IOException {
            try {
                if (claimedIdOrNull == null) throw new IOException("no id");
                BundleUtils.checkIdClean(claimedIdOrNull);
                Direction d = Direction.UPSTREAM;
                Path target = dirs.get(d).resolve(claimedIdOrNull);
                Files.move(s.path(), target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                BundleRecord r = new BundleRecord(claimedIdOrNull, d, Files.size(target), System.currentTimeMillis());
                committed.get(d).put(claimedIdOrNull, r);
                return r;
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(s.path());
                throw e instanceof IOException ? (IOException) e : new IOException(e);
            }
        }

        @Override
        public synchronized void abort(Staged s) {
            aborted.add(s);
            try {
                Files.deleteIfExists(s.path());
            } catch (IOException ignored) {
                // contract: never throws
            }
        }

        @Override
        public synchronized List<BundleRecord> list(Direction d) {
            return Collections.unmodifiableList(new ArrayList<>(committed.get(d).values()));
        }

        @Override
        public synchronized Path pathFor(Direction d, String bundleId) {
            if (!committed.get(d).containsKey(bundleId)) return null;
            return dirs.get(d).resolve(bundleId);
        }

        @Override
        public synchronized boolean contains(Direction d, String bundleId) {
            return committed.get(d).containsKey(bundleId);
        }

        @Override
        public synchronized StoreStats stats() {
            long up = 0, down = 0, staged = 0;
            for (BundleRecord r : committed.get(Direction.UPSTREAM).values()) up += r.sizeBytes();
            for (BundleRecord r : committed.get(Direction.DOWNSTREAM).values()) down += r.sizeBytes();
            try (Stream<Path> s = Files.list(staging)) {
                for (Path p : (Iterable<Path>) s::iterator) staged += Files.size(p);
            } catch (IOException ignored) {
                // best effort
            }
            return new StoreStats(committed.get(Direction.UPSTREAM).size(), up,
                                  committed.get(Direction.DOWNSTREAM).size(), down, staged, capacityBytes);
        }

        @Override
        public synchronized boolean hasCapacityFor(Direction d, long bytes) {
            return stats().usedBytes() + bytes <= capacityBytes;
        }

        @Override
        public synchronized boolean touch(Direction d, String bundleId) {
            BundleRecord r = committed.get(d).get(bundleId);
            if (r == null) return false;
            committed.get(d).put(bundleId, r.withLastSeen(System.currentTimeMillis()));
            return true;
        }

        /** Adds a DOWNSTREAM record with explicit jar metadata (client tag, build time, last seen). */
        synchronized void putDownstream(String id, byte[] bytes, String clientTag, long builtAt, long committedAt,
                                        long lastSeen) throws IOException {
            Files.write(dirs.get(Direction.DOWNSTREAM).resolve(id), bytes);
            committed.get(Direction.DOWNSTREAM).put(id, new BundleRecord(id, Direction.DOWNSTREAM, bytes.length,
                                                                         committedAt, lastSeen, clientTag, builtAt));
        }

        @Override
        public synchronized void delete(Direction d, String bundleId) {
            committed.get(d).remove(bundleId);
            try {
                Files.deleteIfExists(dirs.get(d).resolve(bundleId));
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    /** In-memory blob store that accepts anything offered. */
    static final class FakeRecencyBlobStore implements RecencyBlobStore {
        volatile byte[] blob;

        @Override
        public Optional<byte[]> current() {
            return Optional.ofNullable(blob);
        }

        @Override
        public boolean offer(byte[] rawResponse) {
            blob = rawResponse;
            return true;
        }

        @Override
        public long currentTimestamp() {
            return 0;
        }
    }

    /** Records served ids. */
    static final class FakeDeliveryLog implements DeliveryLog {
        final List<String> served = new CopyOnWriteArrayList<>();

        @Override
        public void recordServed(String downstreamBundleId, long atMillis) {
            served.add(downstreamBundleId);
        }
    }

    /** Records listener events. */
    static final class RecordingListener implements RpcEventListener {
        final List<Integer> sessionCounts = new CopyOnWriteArrayList<>();
        final List<String> rejected = new CopyOnWriteArrayList<>();
        final List<BundleRecord> committed = new CopyOnWriteArrayList<>();
        final List<String> failed = new CopyOnWriteArrayList<>();
        final List<String> served = new CopyOnWriteArrayList<>();

        @Override
        public void onActiveSessionsChanged(int activeSessions) {
            sessionCounts.add(activeSessions);
        }

        @Override
        public void onClientRejected(String remoteIp, String fullMethod) {
            rejected.add(remoteIp + " " + fullMethod);
        }

        @Override
        public void onUploadCommitted(BundleRecord record) {
            committed.add(record);
        }

        @Override
        public void onUploadFailed(String claimedBundleId, String reason) {
            failed.add(claimedBundleId + ": " + reason);
        }

        @Override
        public void onDownloadServed(String bundleId) {
            served.add(bundleId);
        }
    }

    /** Captures what a service method sends to its response observer. */
    static final class RecordingObserver<T> implements StreamObserver<T> {
        final List<T> values = new CopyOnWriteArrayList<>();
        volatile Throwable error;
        volatile boolean completed;

        @Override
        public void onNext(T value) {
            values.add(value);
        }

        @Override
        public void onError(Throwable t) {
            error = t;
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }
}
