package net.discdd.client.bundlerouting;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.discdd.pathutils.ClientPaths;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static net.discdd.client.bundlerouting.ClientRouting.TRANSPORT_EXPIRATION_MS;

public class ClientRoutingTest {
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    @TempDir
    Path rootDir;

    private final AtomicLong now = new AtomicLong(1_000_000_000_000L);

    private ClientRouting newRouting() throws Exception {
        return new ClientRouting(new ClientPaths(rootDir, null, null, null), now::get);
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
        var routing = newRouting();
        routing.updateMetaData("T1");
        now.addAndGet(TRANSPORT_EXPIRATION_MS - DAY_MS);
        routing.updateMetaData("T2");

        Assertions.assertEquals(Map.of("T1", 1L, "T2", 1L), sent(routing));

        now.addAndGet(2 * DAY_MS);
        Assertions.assertEquals(Map.of("T2", 1L), sent(routing));
    }

    @Test
    void revisitKeepsTransport() throws Exception {
        var routing = newRouting();
        routing.updateMetaData("T1");
        now.addAndGet(TRANSPORT_EXPIRATION_MS - DAY_MS);
        routing.updateMetaData("T1");
        now.addAndGet(2 * DAY_MS);

        Assertions.assertEquals(Map.of("T1", 1L), sent(routing));
    }

    @Test
    void visitTimesSurviveRestart() throws Exception {
        newRouting().updateMetaData("T1");
        now.addAndGet(TRANSPORT_EXPIRATION_MS + DAY_MS);

        Assertions.assertEquals(Map.of(), sent(newRouting()));
    }

    @Test
    void transportsFromBeforeVisitTimesExpireLater() throws Exception {
        var paths = new ClientPaths(rootDir, null, null, null);
        Files.writeString(paths.metadataFile.toPath(), "{\"T1\":4}");

        var routing = newRouting();
        Assertions.assertFalse(Files.exists(paths.metadataFile.toPath()));
        now.addAndGet(TRANSPORT_EXPIRATION_MS - DAY_MS);
        Assertions.assertEquals(Map.of("T1", 1L), sent(routing));

        now.addAndGet(2 * DAY_MS);
        Assertions.assertEquals(Map.of(), sent(routing));
    }
}
