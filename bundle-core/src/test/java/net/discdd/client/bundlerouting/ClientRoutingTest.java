package net.discdd.client.bundlerouting;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.discdd.pathutils.ClientPaths;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static net.discdd.client.bundlerouting.ClientRouting.TRANSPORT_EXPIRATION_MS;

public class ClientRoutingTest {
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    @TempDir
    Path rootDir;

    private ClientPaths paths() throws Exception {
        return new ClientPaths(rootDir, null, null, null);
    }

    private ClientRouting newRouting() throws Exception {
        return new ClientRouting(paths());
    }

    // pretend each transport was last visited this long ago
    private void visitedAgo(Map<String, Long> agoMs) throws Exception {
        long now = System.currentTimeMillis();
        var lastSeen = new HashMap<String, Long>();
        agoMs.forEach((transportId, ago) -> lastSeen.put(transportId, now - ago));
        new ObjectMapper().writeValue(paths().transportLastSeenFile, lastSeen);
    }

    private static Map<String, Long> sent(ClientRouting routing) throws Exception {
        return new ObjectMapper().readValue(routing.bundleMetaData(), new TypeReference<Map<String, Long>>() {});
    }

    @Test
    void everySeenTransportIsListedOnce() throws Exception {
        var routing = newRouting();
        routing.updateMetaData("T1");
        routing.updateMetaData("T1");
        routing.updateMetaData("T2");

        Assertions.assertEquals(Map.of("T1", 1L, "T2", 1L), sent(routing));
    }

    @Test
    void transportNotVisitedRecentlyIsDropped() throws Exception {
        visitedAgo(Map.of("T1", TRANSPORT_EXPIRATION_MS + DAY_MS, "T2", TRANSPORT_EXPIRATION_MS - DAY_MS));
        Assertions.assertEquals(Map.of("T2", 1L), sent(newRouting()));
    }

    @Test
    void revisitKeepsTransport() throws Exception {
        visitedAgo(Map.of("T1", TRANSPORT_EXPIRATION_MS + DAY_MS));
        var routing = newRouting();
        routing.updateMetaData("T1");

        Assertions.assertEquals(Map.of("T1", 1L), sent(routing));
    }

    @Test
    void visitTimesSurviveRestart() throws Exception {
        long before = System.currentTimeMillis();
        newRouting().updateMetaData("T1");

        var saved = new ObjectMapper().readValue(paths().transportLastSeenFile,
                                                 new TypeReference<Map<String, Long>>() {});
        Assertions.assertTrue(saved.get("T1") >= before);
        Assertions.assertEquals(Map.of("T1", 1L), sent(newRouting()));
    }

    @Test
    void transportsFromOldMetadataFileAreCarriedOver() throws Exception {
        Files.writeString(paths().metadataFile.toPath(), "{\"T1\":4}");

        var routing = newRouting();
        Assertions.assertFalse(Files.exists(paths().metadataFile.toPath()));
        Assertions.assertEquals(Map.of("T1", 1L), sent(routing));
    }
}
