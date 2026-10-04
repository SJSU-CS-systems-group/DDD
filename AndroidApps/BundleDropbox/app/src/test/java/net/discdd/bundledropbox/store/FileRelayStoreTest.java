package net.discdd.bundledropbox.store;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * JVM tests for {@link FileRelayStore}: staging, commit (id resolution, replace, rejects), abort,
 * startup reconcile, stats/capacity, ordering, delete and concurrent commits.
 */
public class FileRelayStoreTest {
    private static final long CAPACITY = 10 * 1024 * 1024;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private DropboxPaths paths;
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private FileRelayStore store;

    @Before
    public void setUp() throws IOException {
        paths = new DropboxPaths(tmp.getRoot().toPath());
        store = newStore();
    }

    private FileRelayStore newStore() throws IOException {
        return new FileRelayStore(paths, CAPACITY, () -> clock.addAndGet(10));
    }

    /** Stages a jar bundle with the given {@code bundle.id} and closes the stream. */
    private Staged stageJar(Direction d, String id, int payloadBytes) throws IOException {
        Staged s = store.beginStaging(d);
        try (OutputStream out = s.open()) {
            TestBundles.writeJar(out, id, payloadBytes);
        }
        return s;
    }

    private Staged stageRaw(Direction d, byte[] bytes) throws IOException {
        Staged s = store.beginStaging(d);
        try (OutputStream out = s.open()) {
            out.write(bytes);
        }
        return s;
    }

    private long stagingFileCount() throws IOException {
        try (var files = Files.list(paths.staging)) {
            return files.count();
        }
    }

    @Test
    public void stagingIsInvisibleUntilCommit() throws IOException {
        Staged s = stageJar(Direction.DOWNSTREAM, "idA", 100);
        assertTrue(s.path().startsWith(paths.staging));
        assertTrue(store.list(Direction.DOWNSTREAM).isEmpty());
        assertFalse(store.contains(Direction.DOWNSTREAM, "idA"));
        assertEquals(0, store.stats().downstreamCount());
    }

    @Test
    public void commitUsesJarIdWhenNoClaim() throws IOException {
        Staged s = stageJar(Direction.DOWNSTREAM, "idFromJar", 100);
        long size = Files.size(s.path());
        BundleRecord r = store.commit(s, null);
        assertEquals("idFromJar", r.bundleId());
        assertEquals(Direction.DOWNSTREAM, r.direction());
        assertEquals(size, r.sizeBytes());
        Path p = store.pathFor(Direction.DOWNSTREAM, "idFromJar");
        assertEquals(paths.downstream.resolve("idFromJar"), p);
        assertTrue(Files.isRegularFile(p));
        assertFalse(Files.exists(s.path()));
        assertEquals(0, stagingFileCount());
        // Directions are independent queues.
        assertFalse(store.contains(Direction.UPSTREAM, "idFromJar"));
    }

    @Test
    public void commitUsesClaimForNonJarAndKeepsBytes() throws IOException {
        byte[] bytes = {9, 8, 7, 6, 5};
        Staged s = stageRaw(Direction.UPSTREAM, bytes);
        store.commit(s, "claimed1");
        assertArrayEquals(bytes, Files.readAllBytes(store.pathFor(Direction.UPSTREAM, "claimed1")));
    }

    @Test
    public void commitAcceptsMatchingClaimAndJarId() throws IOException {
        BundleRecord r = store.commit(stageJar(Direction.UPSTREAM, "same", 10), "same");
        assertEquals("same", r.bundleId());
    }

    @Test
    public void idMismatchIsRejectedAndStagedFileDeleted() throws IOException {
        Staged s = stageJar(Direction.UPSTREAM, "jarId", 10);
        try {
            store.commit(s, "otherId");
            fail("expected IOException");
        } catch (IOException expected) {
        }
        assertFalse(Files.exists(s.path()));
        assertTrue(store.list(Direction.UPSTREAM).isEmpty());
        assertEquals(0, store.stats().stagedBytes());
    }

    @Test
    public void uncleanClaimIsRejected() throws IOException {
        Staged s = stageRaw(Direction.UPSTREAM, new byte[]{1});
        try {
            store.commit(s, "../escape");
            fail("expected IOException");
        } catch (IOException expected) {
        }
        assertFalse(Files.exists(tmp.getRoot().toPath().resolve("Dropbox/escape")));
        assertTrue(store.list(Direction.UPSTREAM).isEmpty());
    }

    @Test
    public void noIdAtAllIsRejected() throws IOException {
        Staged s = stageRaw(Direction.DOWNSTREAM, new byte[]{1, 2});
        try {
            store.commit(s, null);
            fail("expected IOException");
        } catch (IOException expected) {
        }
        assertEquals(0, stagingFileCount());
    }

    @Test
    public void commitTwiceFails() throws IOException {
        Staged s = stageJar(Direction.UPSTREAM, "once", 10);
        store.commit(s, null);
        try {
            store.commit(s, null);
            fail("expected IOException");
        } catch (IOException expected) {
        }
        assertTrue(store.contains(Direction.UPSTREAM, "once"));
    }

    @Test
    public void commitClosesAStillOpenStream() throws IOException {
        Staged s = store.beginStaging(Direction.UPSTREAM);
        OutputStream out = s.open();
        out.write(new byte[]{1, 2, 3});
        // Not closed by the writer: commit must flush/close it itself.
        store.commit(s, "openStream");
        assertEquals(3, Files.size(store.pathFor(Direction.UPSTREAM, "openStream")));
    }

    @Test
    public void reopenTruncates() throws IOException {
        Staged s = store.beginStaging(Direction.UPSTREAM);
        try (OutputStream out = s.open()) {
            out.write(new byte[100]);
        }
        try (OutputStream out = s.open()) {
            out.write(new byte[]{1});
        }
        assertEquals(1, store.commit(s, "re").sizeBytes());
    }

    @Test
    public void abortDeletesAndIsIdempotent() throws IOException {
        Staged s = stageJar(Direction.DOWNSTREAM, "gone", 50);
        assertTrue(store.stats().stagedBytes() > 0);
        store.abort(s);
        store.abort(s);
        assertFalse(Files.exists(s.path()));
        assertEquals(0, store.stats().stagedBytes());
        try {
            store.commit(s, null);
            fail("commit after abort must fail");
        } catch (IOException expected) {
        }
        try {
            s.open();
            fail("open after abort must fail");
        } catch (IOException expected) {
        }
    }

    @Test
    public void abortClosesOpenStream() throws IOException {
        Staged s = store.beginStaging(Direction.DOWNSTREAM);
        OutputStream out = s.open();
        out.write(1);
        store.abort(s);
        assertFalse(Files.exists(s.path()));
        try {
            out.write(new byte[8192]);
            out.flush();
            fail("writer should see the cancelled stream");
        } catch (IOException expected) {
        }
    }

    @Test
    public void abortAfterCommitIsNoop() throws IOException {
        Staged s = stageJar(Direction.UPSTREAM, "kept", 10);
        store.commit(s, null);
        store.abort(s);
        assertTrue(store.contains(Direction.UPSTREAM, "kept"));
    }

    @Test
    public void sameIdReplacesExisting() throws IOException {
        store.commit(stageJar(Direction.DOWNSTREAM, "dup", 10), null);
        BundleRecord second = store.commit(stageJar(Direction.DOWNSTREAM, "dup", 5000), null);
        List<BundleRecord> list = store.list(Direction.DOWNSTREAM);
        assertEquals(1, list.size());
        assertEquals(second, list.get(0));
        assertEquals(Files.size(store.pathFor(Direction.DOWNSTREAM, "dup")), second.sizeBytes());
        assertEquals(1, store.stats().downstreamCount());
        assertEquals(second.sizeBytes(), store.stats().downstreamBytes());
    }

    @Test
    public void listIsSortedOldestFirst() throws IOException {
        store.commit(stageJar(Direction.UPSTREAM, "b", 10), null);
        store.commit(stageJar(Direction.UPSTREAM, "a", 10), null);
        store.commit(stageJar(Direction.UPSTREAM, "c", 10), null);
        List<String> ids = new ArrayList<>();
        for (BundleRecord r : store.list(Direction.UPSTREAM)) ids.add(r.bundleId());
        assertEquals(List.of("b", "a", "c"), ids);
    }

    @Test
    public void reconcileRemovesLeftoverStagingAndIgnoresNonBundles() throws IOException {
        BundleRecord kept = store.commit(stageJar(Direction.UPSTREAM, "survivor", 10), null);
        // Simulate a crash mid-receive plus stray files in the queue directories.
        Files.write(paths.staging.resolve("upstream-crashed.part"), new byte[]{1, 2, 3});
        Files.write(paths.downstream.resolve("recencyBlob.bin"), new byte[]{1});
        Files.write(paths.downstream.resolve("x.tmp-123"), new byte[]{1});
        Files.createDirectories(paths.downstream.resolve("subdir"));

        FileRelayStore reopened = newStore();
        assertEquals(0, stagingFileCount());
        assertTrue(reopened.list(Direction.DOWNSTREAM).isEmpty());
        assertEquals(0, reopened.stats().stagedBytes());
        List<BundleRecord> up = reopened.list(Direction.UPSTREAM);
        assertEquals(1, up.size());
        assertEquals("survivor", up.get(0).bundleId());
        assertEquals(kept.sizeBytes(), up.get(0).sizeBytes());
        // committedAt survives the restart through the file mtime.
        assertEquals(kept.committedAtMillis(), up.get(0).committedAtMillis());
        assertNull(reopened.pathFor(Direction.DOWNSTREAM, "recencyBlob.bin"));
    }

    @Test
    public void reconcileKeepsLiveStagedFiles() throws IOException {
        Staged live = stageJar(Direction.UPSTREAM, "live", 10);
        Files.write(paths.staging.resolve("dead.part"), new byte[]{1});
        assertEquals(1, store.reconcile());
        assertTrue(Files.exists(live.path()));
        assertEquals("live", store.commit(live, null).bundleId());
    }

    @Test
    public void statsCountCommittedAndStagedBytes() throws IOException {
        BundleRecord up = store.commit(stageJar(Direction.UPSTREAM, "u1", 100), null);
        BundleRecord down1 = store.commit(stageJar(Direction.DOWNSTREAM, "d1", 200), null);
        BundleRecord down2 = store.commit(stageJar(Direction.DOWNSTREAM, "d2", 300), null);
        Staged pending = stageRaw(Direction.UPSTREAM, new byte[1234]);

        StoreStats st = store.stats();
        assertEquals(1, st.upstreamCount());
        assertEquals(up.sizeBytes(), st.upstreamBytes());
        assertEquals(2, st.downstreamCount());
        assertEquals(down1.sizeBytes() + down2.sizeBytes(), st.downstreamBytes());
        assertEquals(1234, st.stagedBytes());
        assertEquals(CAPACITY, st.capacityBytes());
        assertEquals(up.sizeBytes() + down1.sizeBytes() + down2.sizeBytes() + 1234, st.usedBytes());

        store.abort(pending);
        assertEquals(0, store.stats().stagedBytes());
    }

    @Test
    public void capacityCheckIncludesStagedBytes() throws IOException {
        store.setCapacityBytes(5_000);
        assertTrue(store.hasCapacityFor(Direction.UPSTREAM, 5_000));
        assertFalse(store.hasCapacityFor(Direction.UPSTREAM, 5_001));
        Staged s = stageRaw(Direction.UPSTREAM, new byte[3_000]);
        assertTrue(store.hasCapacityFor(Direction.UPSTREAM, 2_000));
        assertFalse(store.hasCapacityFor(Direction.UPSTREAM, 2_001));
        store.commit(s, "big");
        assertFalse(store.hasCapacityFor(Direction.UPSTREAM, 2_001));   // committed bytes still count
        assertTrue(store.hasCapacityFor(Direction.UPSTREAM, 0));
        store.delete(Direction.UPSTREAM, "big");
        assertTrue(store.hasCapacityFor(Direction.UPSTREAM, 5_000));
        assertEquals(2_000, new StoreStats(0, 3_000, 0, 0, 0, 5_000).freeBytes());
    }

    /** Audit M10: download-all can never take the share reserved for client uploads. */
    @Test
    public void downstreamCannotUseTheUpstreamReserve() throws IOException {
        store.setCapacityBytes(10_000);   // default reserve 25% -> downstream quota 7,500
        assertEquals(7_500, store.downstreamQuotaBytes());
        assertTrue(store.hasCapacityFor(Direction.DOWNSTREAM, 7_500));
        assertFalse(store.hasCapacityFor(Direction.DOWNSTREAM, 7_501));

        // Downstream staged bytes count against the quota while they stream in.
        Staged down = stageRaw(Direction.DOWNSTREAM, new byte[7_000]);
        assertTrue(store.hasCapacityFor(Direction.DOWNSTREAM, 500));
        assertFalse(store.hasCapacityFor(Direction.DOWNSTREAM, 501));
        store.commit(down, "downFull");
        assertFalse("downstream quota reached", store.hasCapacityFor(Direction.DOWNSTREAM, 501));
        assertTrue("uploads still have the reserve", store.hasCapacityFor(Direction.UPSTREAM, 3_000));
        assertFalse(store.hasCapacityFor(Direction.UPSTREAM, 3_001));

        // Upstream may use space downstream leaves free, which then also limits downstream (total cap).
        store.commit(stageRaw(Direction.UPSTREAM, new byte[2_900]), "up1");
        assertFalse(store.hasCapacityFor(Direction.DOWNSTREAM, 101));
        assertTrue(store.hasCapacityFor(Direction.DOWNSTREAM, 100));

        store.setUpstreamReservePercent(0);   // v1 behaviour: one shared budget
        assertEquals(10_000, store.downstreamQuotaBytes());
    }

    @Test(expected = IllegalArgumentException.class)
    public void reservePercentIsBounded() {
        store.setUpstreamReservePercent(95);
    }

    /** Audit M7 groundwork: commit records the plaintext client tag and the jar build time. */
    @Test
    public void commitRecordsClientTagAndBuildTime() throws IOException {
        Staged a = store.beginStaging(Direction.DOWNSTREAM);
        try (OutputStream out = a.open()) {
            TestBundles.writeJar(out, "v1", 10, "Ab_c-key", 1_700_000_000_000L);
        }
        BundleRecord r1 = store.commit(a, null);
        Staged b = store.beginStaging(Direction.DOWNSTREAM);
        try (OutputStream out = b.open()) {
            TestBundles.writeJar(out, "v2", 10, "Ab_c-key", 1_700_000_100_000L);
        }
        BundleRecord r2 = store.commit(b, null);
        BundleRecord plain = store.commit(stageJar(Direction.DOWNSTREAM, "noKey", 10), null);

        assertNotNull(r1.clientTag());
        assertEquals("same clientBase.pub, same tag", r1.clientTag(), r2.clientTag());
        assertEquals(BundleIds.clientTag("-----BEGIN EC PUBLIC KEY-----\nAb_c-key\n-----END EC PUBLIC KEY-----"),
                     r1.clientTag());
        assertEquals(1_700_000_000_000L, r1.builtAtMillis());
        assertEquals(1_700_000_100_000L, r2.builtAtMillis());
        assertNull(plain.clientTag());

        // Rebuilt from the jars on restart.
        FileRelayStore reopened = newStore();
        for (BundleRecord r : reopened.list(Direction.DOWNSTREAM)) {
            if (r.bundleId().equals("v2")) {
                assertEquals(r2.clientTag(), r.clientTag());
                assertEquals(r2.builtAtMillis(), r.builtAtMillis());
            }
        }
    }

    /** Audit M9: a re-offered bundle's last-seen time advances and survives a restart (via mtime). */
    @Test
    public void touchAdvancesLastSeenAndPersistsIt() throws IOException {
        BundleRecord r = store.commit(stageJar(Direction.DOWNSTREAM, "current", 10), null);
        assertEquals(r.committedAtMillis(), r.lastSeenAtMillis());
        assertTrue(store.touch(Direction.DOWNSTREAM, "current"));
        BundleRecord touched = store.list(Direction.DOWNSTREAM).get(0);
        assertTrue(touched.lastSeenAtMillis() > r.lastSeenAtMillis());
        assertEquals("commit time unchanged in memory", r.committedAtMillis(), touched.committedAtMillis());
        assertFalse(store.touch(Direction.DOWNSTREAM, "absent"));
        assertFalse(store.touch(Direction.UPSTREAM, "current"));
        assertFalse(store.touch(Direction.DOWNSTREAM, "../x"));

        BundleRecord reloaded = newStore().list(Direction.DOWNSTREAM).get(0);
        assertEquals(touched.lastSeenAtMillis(), reloaded.lastSeenAtMillis());
    }

    /** Audit i9: the "newest first" upload order rests on committedAt with a bundle-id tie-break. */
    @Test
    public void listOrderingBasisIsCommitTimeThenIdAndSurvivesRestart() throws IOException {
        AtomicLong frozen = new AtomicLong(5_000_000L);
        FileRelayStore s = new FileRelayStore(paths, CAPACITY, frozen::get);
        s.commit(stageWith(s, "zeta"), null);
        s.commit(stageWith(s, "alpha"), null);   // same commit time as zeta: id decides
        frozen.set(6_000_000L);
        s.commit(stageWith(s, "mid"), null);
        List<String> ids = new ArrayList<>();
        for (BundleRecord r : s.list(Direction.UPSTREAM)) ids.add(r.bundleId());
        assertEquals(List.of("alpha", "zeta", "mid"), ids);

        List<String> afterRestart = new ArrayList<>();
        for (BundleRecord r : new FileRelayStore(paths, CAPACITY, frozen::get).list(Direction.UPSTREAM)) {
            afterRestart.add(r.bundleId());
        }
        assertEquals(ids, afterRestart);
    }

    private static Staged stageWith(FileRelayStore s, String id) throws IOException {
        Staged st = s.beginStaging(Direction.UPSTREAM);
        try (OutputStream out = st.open()) {
            TestBundles.writeJar(out, id, 10);
        }
        return st;
    }

    /** Audit m8: the rename is followed by a directory fsync, which must never fail the commit. */
    @Test
    public void directoryFsyncIsBestEffort() throws IOException {
        AtomicFiles.fsyncDirectory(paths.upstream);
        AtomicFiles.fsyncDirectory(paths.root.resolve("does-not-exist"));
        AtomicFiles.fsyncDirectory(null);
        Path target = paths.meta.resolve("x.bin");
        AtomicFiles.write(target, new byte[]{1, 2});
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(target));
    }

    @Test(expected = IllegalArgumentException.class)
    public void capacityMustBePositive() {
        store.setCapacityBytes(0);
    }

    @Test
    public void deleteRemovesFileAndIgnoresMissingOrUnclean() throws IOException {
        store.commit(stageJar(Direction.DOWNSTREAM, "del", 10), null);
        Path p = store.pathFor(Direction.DOWNSTREAM, "del");
        store.delete(Direction.DOWNSTREAM, "del");
        assertFalse(Files.exists(p));
        assertFalse(store.contains(Direction.DOWNSTREAM, "del"));
        assertNull(store.pathFor(Direction.DOWNSTREAM, "del"));
        store.delete(Direction.DOWNSTREAM, "never-existed");
        store.delete(Direction.DOWNSTREAM, "../x");
        store.delete(Direction.DOWNSTREAM, null);
    }

    @Test
    public void pathForUncleanOrAbsentIsNull() {
        assertNull(store.pathFor(Direction.UPSTREAM, "absent"));
        assertNull(store.pathFor(Direction.UPSTREAM, "../etc"));
        assertNull(store.pathFor(Direction.UPSTREAM, null));
        assertFalse(store.contains(Direction.UPSTREAM, null));
    }

    @Test
    public void foreignStagedHandleIsRejected() throws IOException {
        FileRelayStore other = new FileRelayStore(new DropboxPaths(tmp.newFolder().toPath()), CAPACITY);
        Staged foreign = other.beginStaging(Direction.UPSTREAM);
        try {
            store.commit(foreign, "x");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
        store.abort(foreign);                      // ignored, never throws
        assertTrue(Files.exists(foreign.path()));
    }

    @Test
    public void concurrentCommitsAreAllIndexed() throws Exception {
        int n = 40;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<BundleRecord>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final String id = "c" + (i % 20);   // half the ids collide: exercises replace under contention
                futures.add(pool.submit(() -> store.commit(stageJar(Direction.DOWNSTREAM, id, 64), null)));
            }
            for (Future<BundleRecord> f : futures) assertNotNull(f.get());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(20, store.list(Direction.DOWNSTREAM).size());
        assertEquals(0, stagingFileCount());
        assertEquals(20, newStore().stats().downstreamCount());
    }
}
