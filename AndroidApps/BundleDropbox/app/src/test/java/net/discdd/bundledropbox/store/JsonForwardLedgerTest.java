package net.discdd.bundledropbox.store;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM tests for {@link JsonForwardLedger}: per-carrier tracking, persistence, pruning, corruption. */
public class JsonForwardLedgerTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private DropboxPaths paths;

    @Before
    public void setUp() throws IOException {
        paths = new DropboxPaths(tmp.getRoot().toPath()).createDirectories();
    }

    @Test
    public void tracksPairsPerCarrier() {
        JsonForwardLedger l = new JsonForwardLedger(paths);
        assertFalse(l.wasForwardedTo("b1", "T1"));
        l.recordForwarded("b1", "T1", 100);
        assertTrue(l.wasForwardedTo("b1", "T1"));
        assertFalse("other carrier still needs it", l.wasForwardedTo("b1", "T2"));
        assertFalse(l.wasForwardedTo("b2", "T1"));
        l.recordForwarded("b1", "T2", 200);
        assertEquals(Map.of("T1", 100L, "T2", 200L), l.carriersFor("b1"));
        assertEquals(1, l.size());
    }

    @Test
    public void persistsAcrossRestart() {
        JsonForwardLedger l = new JsonForwardLedger(paths);
        l.recordForwarded("b1", "T1", 100);
        l.recordForwarded("b2", "T1", 150);
        l.recordForwarded("b1", "T1", 300);   // re-record updates the time

        JsonForwardLedger reopened = new JsonForwardLedger(paths);
        assertTrue(reopened.wasForwardedTo("b1", "T1"));
        assertTrue(reopened.wasForwardedTo("b2", "T1"));
        assertEquals(Map.of("T1", 300L), reopened.carriersFor("b1"));
        assertTrue(Files.exists(paths.meta.resolve(JsonForwardLedger.FILE_NAME)));
    }

    @Test
    public void pruneDropsMissingBundlesAndPersists() {
        JsonForwardLedger l = new JsonForwardLedger(paths);
        l.recordForwarded("keep", "T1", 1);
        l.recordForwarded("gone", "T1", 2);
        assertEquals(1, l.prune(Set.of("keep")::contains));
        assertTrue(l.wasForwardedTo("keep", "T1"));
        assertFalse(l.wasForwardedTo("gone", "T1"));
        assertFalse(new JsonForwardLedger(paths).wasForwardedTo("gone", "T1"));
        assertEquals(0, l.prune(id -> true));
    }

    @Test
    public void pruneAgainstStoreUsesUpstreamQueue() throws IOException {
        FileRelayStore store = new FileRelayStore(paths, 1 << 20);
        Staged s = store.beginStaging(Direction.UPSTREAM);
        try (var out = s.open()) {
            out.write(1);
        }
        store.commit(s, "inStore");
        JsonForwardLedger l = new JsonForwardLedger(paths);
        l.recordForwarded("inStore", "T1", 1);
        l.recordForwarded("cleared", "T1", 1);
        assertEquals(1, l.pruneAgainst(store));
        assertEquals(1, l.size());
        assertTrue(l.wasForwardedTo("inStore", "T1"));
    }

    @Test
    public void corruptFileStartsEmptyAndIsMovedAside() throws IOException {
        Path f = paths.meta.resolve(JsonForwardLedger.FILE_NAME);
        Files.write(f, "{ not json".getBytes(StandardCharsets.UTF_8));
        JsonForwardLedger l = new JsonForwardLedger(paths);
        assertEquals(0, l.size());
        assertTrue(Files.exists(f.resolveSibling(JsonForwardLedger.FILE_NAME + ".corrupt")));
        l.recordForwarded("b", "T", 1);
        assertTrue(new JsonForwardLedger(paths).wasForwardedTo("b", "T"));
    }

    @Test
    public void emptyFileIsTolerated() throws IOException {
        Files.write(paths.meta.resolve(JsonForwardLedger.FILE_NAME), new byte[0]);
        assertEquals(0, new JsonForwardLedger(paths).size());
    }
}
