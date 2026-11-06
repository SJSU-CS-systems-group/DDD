package net.discdd.server.bundlerouting;

import net.discdd.server.repository.ReceivedBundleRepository;
import net.discdd.server.repository.RoutingEdgeRepository;
import net.discdd.server.repository.entity.RoutingEdge;
import net.discdd.server.repository.entity.RoutingEdgeId;
import net.discdd.server.service.GrpcExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H2-backed tests of {@link BundleRouting} and {@link RoutingSweeper} on a real {@link RoutingEdgeRepository}. */
@DataJpaTest
@Import({ GrpcExecutor.class })
class BundleRoutingTest {
    private static final Duration TTL = Duration.ofDays(14);
    private static final long NOW = 1_800_000_000_000L;
    private static final String T = "carrierT";
    private static final String C = "clientC";
    @TempDir
    static Path staticTempDir;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("bundle-server.bundle-store-root", () -> staticTempDir.toString());
    }

    @Autowired
    RoutingEdgeRepository routingEdgeRepository;
    @Autowired
    ReceivedBundleRepository receivedBundleRepository;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    TestEntityManager entityManager;

    private BundleRouting routing;

    @BeforeEach
    void setUp() {
        routing = new BundleRouting(routingEdgeRepository, receivedBundleRepository, TTL);
    }

    /** the bulk touch bypasses the persistence context; read what the database holds */
    private long lastSeen(String peer, String client) {
        entityManager.flush();
        entityManager.clear();
        return routingEdgeRepository.findById(new RoutingEdgeId(peer, client, RoutingSource.UPLOADED))
                .orElseThrow().lastSeenMs;
    }

    @Test
    void uploadedInsertThenTouchKeepsMaxLastSeen() {
        routing.recordUploaded(T, C, NOW);
        assertEquals(NOW, lastSeen(T, C));
        routing.recordUploaded(T, C, NOW + 1000);
        assertEquals(NOW + 1000, lastSeen(T, C));
        routing.recordUploaded(T, C, NOW + 500);
        assertEquals(NOW + 1000, lastSeen(T, C), "an older sighting never moves lastSeen back");
        assertEquals(1, routingEdgeRepository.count());
    }

    @Test
    void blankOrOverlongPeerIgnored() {
        routing.recordUploaded(null, C, NOW);
        routing.recordUploaded("", C, NOW);
        routing.recordUploaded("   ", C, NOW);
        routing.recordUploaded("p".repeat(RoutingEdgeId.MAX_ID_CHARS + 1), C, NOW);
        routing.recordUploaded(T, "c".repeat(RoutingEdgeId.MAX_ID_CHARS + 1), NOW);
        routing.recordUploaded(T, null, NOW);
        assertEquals(0, routingEdgeRepository.count());
        assertEquals(List.of(), routing.clientsForCarrier("", NOW));
        assertEquals(List.of(), routing.clientsForCarrier(null, NOW));

        routing.recordUploaded("p".repeat(RoutingEdgeId.MAX_ID_CHARS), C, NOW);
        assertEquals(1, routingEdgeRepository.count(), "an ID of exactly the column width is kept");
    }

    @Test
    void staleEdgeNotRouted() {
        routing.recordUploaded(T, "stale", NOW - TTL.toMillis() - 1);
        routing.recordUploaded(T, "edge", NOW - TTL.toMillis());
        assertEquals(List.of("edge"), routing.clientsForCarrier(T, NOW));
    }

    @Test
    void directOrderLastSeenDescThenClientId() {
        routing.recordUploaded(T, "clientB", NOW - 10);
        routing.recordUploaded(T, "clientA", NOW - 10);
        routing.recordUploaded(T, "clientC", NOW - 5);
        routing.recordUploaded(T, "clientD", NOW - 20);
        routing.recordUploaded("otherCarrier", "clientE", NOW);
        // REPORTED edges from the carrier count too; the newest edge per client decides its place
        routingEdgeRepository.save(new RoutingEdge(new RoutingEdgeId(T, "clientD", RoutingSource.REPORTED), NOW));
        assertEquals(List.of("clientD", "clientC", "clientA", "clientB"), routing.clientsForCarrier(T, NOW));
    }

    @Test
    void sweepDeletesOnlyOlderThanTwiceTtl() {
        long twoTtl = 2 * TTL.toMillis();
        // more than one sweep batch of old rows
        for (int i = 0; i <= RoutingSweeper.BATCH; i++) routing.recordUploaded(T, "old" + i, NOW - twoTtl - 1 - i);
        routing.recordUploaded(T, "atTwoTtl", NOW - twoTtl);
        routing.recordUploaded(T, "stale", NOW - TTL.toMillis() - 1);
        routing.recordUploaded(T, "fresh", NOW);
        entityManager.flush();

        var sweeper = new RoutingSweeper(routingEdgeRepository, transactionManager, TTL, Duration.ofHours(6));
        assertEquals(RoutingSweeper.BATCH + 1, sweeper.sweep(NOW));
        entityManager.clear();
        assertEquals(3, routingEdgeRepository.count());
        assertTrue(routingEdgeRepository.existsById(new RoutingEdgeId(T, "atTwoTtl", RoutingSource.UPLOADED)));
        assertTrue(routingEdgeRepository.existsById(new RoutingEdgeId(T, "stale", RoutingSource.UPLOADED)));
        assertFalse(routingEdgeRepository.existsById(new RoutingEdgeId(T, "old0", RoutingSource.UPLOADED)));
        assertEquals(0, sweeper.sweep(NOW));
    }
}
