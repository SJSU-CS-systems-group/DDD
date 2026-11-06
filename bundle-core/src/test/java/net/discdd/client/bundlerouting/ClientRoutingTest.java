package net.discdd.client.bundlerouting;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.pathutils.ClientPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static net.discdd.client.bundlerouting.ClientRouting.GEN_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientRoutingTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long HOUR = 60L * 60 * 1000;
    private static final long DAY = 24 * HOUR;
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String P1 = peer(1);
    private static final String P2 = peer(2);

    @TempDir
    Path root;

    private static String peer(int i) {
        return SecurityUtils.generateID(("peer-" + i).getBytes(StandardCharsets.UTF_8));
    }

    private ClientPaths paths(Path dir) throws Exception {
        return new ClientPaths(dir, null, null, null);
    }

    private static Map<String, Long> parse(byte[] json) throws Exception {
        return mapper.readValue(json, new TypeReference<HashMap<String, Long>>() {});
    }

    private static Map<String, Long> fileOf(ClientPaths paths) throws Exception {
        return parse(Files.readAllBytes(paths.metadataFile.toPath()));
    }

    private static void writeFile(ClientPaths paths, String json) throws Exception {
        Files.writeString(paths.metadataFile.toPath(), json);
    }

    @Test
    void newKeyIsMaterialAndPersisted() throws Exception {
        var paths = paths(root);
        var routing = new ClientRouting(paths, NOW);
        assertFalse(paths.metadataFile.exists(), "nothing to persist yet");
        assertFalse(routing.changedSince(0));

        assertTrue(routing.record(P1, NOW));
        assertEquals(Map.of(P1, NOW), fileOf(paths));
        assertTrue(routing.changedSince(0));
        assertTrue(routing.record(P2, NOW + 1), "a second new key is material too");

        var reloaded = new ClientRouting(paths, NOW + 2);
        assertEquals(Map.of(P1, NOW, P2, NOW + 1, GEN_KEY, NOW + 2), parse(reloaded.bundleMetaData(NOW + 2)));
    }

    @Test
    void refreshWithin24hNotMaterial() throws Exception {
        var paths = paths(root);
        var routing = new ClientRouting(paths, NOW);
        assertTrue(routing.record(P1, NOW));
        long mtime = paths.metadataFile.lastModified();

        assertFalse(routing.record(P1, NOW + DAY - 1));
        assertEquals(Map.of(P1, NOW), fileOf(paths), "a refresh within 24 h is not written");
        assertEquals(mtime, paths.metadataFile.lastModified());
        assertFalse(routing.changedSince(mtime));
        assertEquals(NOW + DAY - 1, parse(routing.bundleMetaData(NOW + DAY)).get(P1), "but it is kept in memory");

        assertFalse(routing.record(P1, NOW - HOUR), "a backwards clock is not material");
        assertEquals(NOW + DAY - 1, parse(routing.bundleMetaData(NOW + DAY)).get(P1), "and never lowers the value");

        assertTrue(routing.record(P1, NOW + DAY), "24 h after the stored value it is material again");
        assertEquals(Map.of(P1, NOW + DAY), fileOf(paths));

        // judged against the file, not the in-memory refreshes: a peer met every 23 h is still written daily
        assertFalse(routing.record(P1, NOW + DAY + 23 * HOUR));
        assertTrue(routing.record(P1, NOW + 2 * DAY));
        assertFalse(new ClientRouting(paths, NOW + 2 * DAY).record(P1, NOW + 2 * DAY + HOUR),
                    "a reload judges against the loaded file");
    }

    @Test
    void prunes30dAtGeneration() throws Exception {
        var routing = new ClientRouting(paths(root), NOW);
        String stale = peer(3);
        routing.record(stale, NOW - 30 * DAY);
        routing.record(P1, NOW - 30 * DAY + 1);

        assertEquals(Map.of(P1, NOW - 30 * DAY + 1, GEN_KEY, NOW), parse(routing.bundleMetaData(NOW)));
    }

    @Test
    void prunesOldEntriesWhenPersisting() throws Exception {
        var paths = paths(root);
        var routing = new ClientRouting(paths, NOW);
        assertTrue(routing.record(P1, NOW));
        assertTrue(routing.record(P2, NOW + 30 * DAY - 1));
        assertEquals(Map.of(P1, NOW, P2, NOW + 30 * DAY - 1), fileOf(paths), "younger than 30 d: kept");

        String p3 = peer(3);
        assertTrue(routing.record(p3, NOW + 30 * DAY));
        assertEquals(Map.of(P2, NOW + 30 * DAY - 1, p3, NOW + 30 * DAY), fileOf(paths), "30 d old: dropped");
        assertTrue(routing.record(P1, NOW + 30 * DAY), "a pruned peer met again is new");
    }

    @Test
    void failedWriteIsNotMaterial() throws Exception {
        var paths = paths(root);
        var routing = new ClientRouting(paths, NOW);
        // a non-empty directory where the file belongs makes the move into place fail
        Path blocker = paths.metadataFile.toPath();
        Files.createDirectories(blocker);
        Files.writeString(blocker.resolve("x"), "x");

        assertFalse(routing.record(P1, NOW));
        Files.delete(blocker.resolve("x"));
        Files.delete(blocker);
        assertTrue(routing.record(P1, NOW + 1), "the next record retries the write");
        assertEquals(Map.of(P1, NOW + 1), fileOf(paths));
    }

    @Test
    void capsNewest32() throws Exception {
        var routing = new ClientRouting(paths(root), NOW);
        for (int i = 0; i < 40; i++) routing.record(peer(i), NOW - i * 1000L);

        var sent = parse(routing.bundleMetaData(NOW));
        assertEquals(ClientRouting.MAX_ENTRIES + 1, sent.size());
        Map<String, Long> expected =
                IntStream.range(0, 32).boxed().collect(Collectors.toMap(ClientRoutingTest::peer, i -> NOW - i * 1000L));
        expected.put(GEN_KEY, NOW);
        assertEquals(expected, sent);
    }

    @Test
    void legacyCountsDroppedOnLoad() throws Exception {
        var paths = paths(root);
        // v1 file: sighting counts, keyed by old TLS names, a USB "" key, and one valid timestamp entry
        writeFile(paths,
                  "{\"" + "A".repeat(122) + "\":7,\"\":2,\"" + P1 + "\":3,\"@gen\":" + NOW + ",\"" + P2 + "\":" +
                          (NOW - HOUR) + "}");

        var routing = new ClientRouting(paths, NOW);
        assertEquals(Map.of(P2, NOW - HOUR, GEN_KEY, NOW), parse(routing.bundleMetaData(NOW)));
        assertEquals(Map.of(P2, NOW - HOUR), fileOf(paths), "the cleaned map is written back once");
        assertTrue(routing.record(P1, NOW), "a dropped key is new again");
    }

    @Test
    void futureValuesClamped() throws Exception {
        var paths = paths(root);
        writeFile(paths, "{\"" + P1 + "\":" + (NOW + 2 * HOUR) + ",\"" + P2 + "\":" + (NOW + HOUR) + "}");

        var routing = new ClientRouting(paths, NOW);
        var sent = parse(routing.bundleMetaData(NOW));
        assertEquals(NOW, sent.get(P1), "more than 1 h ahead is clamped to now");
        assertEquals(NOW + HOUR, sent.get(P2), "within 1 h is kept");
        assertEquals(Map.of(P1, NOW, P2, NOW + HOUR), fileOf(paths));
    }

    @Test
    void corruptFileRecoversEmpty() throws Exception {
        var paths = paths(root);
        writeFile(paths, "{\"" + P1 + "\":12");

        var routing = new ClientRouting(paths, NOW);
        assertEquals(Map.of(GEN_KEY, NOW), parse(routing.bundleMetaData(NOW)));
        assertEquals(Map.of(), fileOf(paths), "the corrupt file is replaced");
        assertTrue(routing.record(P1, NOW));
        assertEquals(Map.of(P1, NOW), fileOf(paths));
    }

    @Test
    void wholeMapAtomicWrite() throws Exception {
        var paths = paths(root);
        writeFile(paths, "{\"" + P1 + "\":" + NOW + "}");
        var routing = new ClientRouting(paths, NOW);
        // an outside write after load is replaced, not merged: the file is always the whole in-memory map
        String outsider = peer(9);
        writeFile(paths, "{\"" + outsider + "\":" + NOW + "}");

        assertTrue(routing.record(P2, NOW + 1));
        assertEquals(Map.of(P1, NOW, P2, NOW + 1), fileOf(paths));
        try (var files = Files.list(paths.metadataFile.toPath().getParent())) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of(paths.metadataFile.getName()), names, "no temp file is left behind");
        }
    }

    @Test
    void bundleMetaDataAddsGenAndParsesAsHashMapStringLong() throws Exception {
        var paths = paths(root);
        var routing = new ClientRouting(paths, NOW);
        assertEquals(Map.of(GEN_KEY, NOW), parse(routing.bundleMetaData(NOW)), "an empty map still carries @gen");

        routing.record(P1, NOW);
        HashMap<String, Long> sent = mapper.readValue(routing.bundleMetaData(NOW + 5), new TypeReference<>() {});
        assertEquals(Map.of(P1, NOW, GEN_KEY, NOW + 5), sent);
        assertFalse(fileOf(paths).containsKey(GEN_KEY), "@gen is never stored");
    }

    @Test
    void twoRootsIndependent() throws Exception {
        var pathsA = paths(root.resolve("a"));
        var pathsB = paths(root.resolve("b"));
        var a = new ClientRouting(pathsA, NOW);
        var b = new ClientRouting(pathsB, NOW);

        assertTrue(a.record(P1, NOW));
        assertTrue(b.record(P2, NOW));
        assertTrue(b.record(P1, NOW), "B has not met P1 even though A has");

        assertEquals(Map.of(P1, NOW, GEN_KEY, NOW), parse(a.bundleMetaData(NOW)));
        assertEquals(Map.of(P1, NOW, P2, NOW, GEN_KEY, NOW), parse(b.bundleMetaData(NOW)));
        assertEquals(Map.of(P1, NOW), fileOf(pathsA));
        assertEquals(Map.of(P1, NOW, P2, NOW), fileOf(pathsB));
    }
}
