package net.discdd.server.bundlerouting;

import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.server.repository.DropboxNodeRepository;
import net.discdd.server.repository.DropboxRecencyGrantRepository;
import net.discdd.server.repository.ReceivedBundleRepository;
import net.discdd.server.repository.RoutingEdgeRepository;
import net.discdd.server.repository.RoutingMetaRepository;
import net.discdd.server.repository.ServerRoutingRepository;
import net.discdd.server.repository.entity.RoutingEdgeId;
import net.discdd.server.repository.entity.RoutingMeta;
import net.discdd.server.repository.entity.ServerRouting;
import net.discdd.server.service.GrpcExecutor;
import net.discdd.tls.DDDTLSUtil;
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
import java.security.PublicKey;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H2-backed tests of the one-time {@code serverroutingtable} to {@code routing_edge} conversion. */
@DataJpaTest
@Import({ GrpcExecutor.class })
class LegacyRoutingImporterTest {
    private static final long NOW = 1_800_000_000_000L;
    @TempDir
    static Path staticTempDir;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("bundle-server.bundle-store-root", () -> staticTempDir.toString());
    }

    @Autowired
    ServerRoutingRepository serverRoutingRepository;
    @Autowired
    RoutingEdgeRepository routingEdgeRepository;
    @Autowired
    RoutingMetaRepository routingMetaRepository;
    @Autowired
    ReceivedBundleRepository receivedBundleRepository;
    @Autowired
    DropboxNodeRepository dropboxNodeRepository;
    @Autowired
    DropboxRecencyGrantRepository dropboxRecencyGrantRepository;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    TestEntityManager entityManager;

    private LegacyRoutingImporter importer;

    @BeforeEach
    void setUp() {
        var routing = new BundleRouting(routingEdgeRepository,
                                        receivedBundleRepository,
                                        dropboxNodeRepository,
                                        dropboxRecencyGrantRepository,
                                        Duration.ofDays(14),
                                        64);
        importer = new LegacyRoutingImporter(serverRoutingRepository, routingMetaRepository, routing,
                                             transactionManager);
    }

    private void legacyRow(String transportId, String clientId, String score) {
        entityManager.persist(new ServerRouting(transportId, clientId, score));
    }

    private static PublicKey tlsKey() throws Exception {
        return DDDTLSUtil.generateKeyPair().getPublic();
    }

    /** writes pending inserts, then reads what the database holds */
    private void reload() {
        entityManager.flush();
        entityManager.clear();
    }

    private Set<String> legacyRows() {
        reload();
        return serverRoutingRepository.findAll()
                .stream()
                .map(r -> r.getServerRoutingId().getTransportID() + "|" + r.getServerRoutingId().getClientID() +
                        "|" + r.getScore())
                .collect(Collectors.toSet());
    }

    @Test
    void convertsTlsNameRowsToUploaded() throws Exception {
        PublicKey t1 = tlsKey();
        PublicKey t2 = tlsKey();
        legacyRow(DDDTLSUtil.publicKeyToName(t1), "clientA", "0");
        legacyRow(DDDTLSUtil.publicKeyToName(t1), "clientB", "0");
        legacyRow(DDDTLSUtil.publicKeyToName(t2), "clientA", "0");
        entityManager.flush();

        assertEquals(3, importer.importOnce(NOW));
        reload();
        String p1 = SecurityUtils.peerIdForTlsKey(t1);
        String p2 = SecurityUtils.peerIdForTlsKey(t2);
        var edge = routingEdgeRepository.findById(new RoutingEdgeId(p1, "clientA", RoutingSource.UPLOADED))
                .orElseThrow();
        assertEquals(NOW, edge.lastSeenMs);
        assertTrue(routingEdgeRepository.existsById(new RoutingEdgeId(p1, "clientB", RoutingSource.UPLOADED)));
        assertTrue(routingEdgeRepository.existsById(new RoutingEdgeId(p2, "clientA", RoutingSource.UPLOADED)));
        assertEquals(3, routingEdgeRepository.count());
        assertEquals(NOW, routingMetaRepository.findById(RoutingMeta.LEGACY_IMPORT_DONE_MS).orElseThrow().valueMs);
    }

    @Test
    void skipsSha1MetadataAndJunkRows() throws Exception {
        PublicKey t1 = tlsKey();
        legacyRow(DDDTLSUtil.publicKeyToName(t1), "clientA", "0");
        // a key from old client routing.metadata: a 28-character SHA-1 ID
        legacyRow(SecurityUtils.generateID(tlsKey().getEncoded()), "clientA", "17");
        legacyRow("not base64!", "clientA", "0");
        legacyRow("AAAA", "clientA", "0");
        legacyRow("", "clientA", "0");
        entityManager.flush();

        assertEquals(1, importer.importOnce(NOW));
        reload();
        assertEquals(1, routingEdgeRepository.count());
        assertTrue(routingEdgeRepository.existsById(new RoutingEdgeId(SecurityUtils.peerIdForTlsKey(t1),
                                                                      "clientA",
                                                                      RoutingSource.UPLOADED)));
    }

    @Test
    void markerBlocksSecondImport() throws Exception {
        legacyRow(DDDTLSUtil.publicKeyToName(tlsKey()), "clientA", "0");
        entityManager.flush();
        assertEquals(1, importer.importOnce(NOW));

        legacyRow(DDDTLSUtil.publicKeyToName(tlsKey()), "clientB", "0");
        entityManager.flush();
        assertEquals(0, importer.importOnce(NOW + 1000));
        reload();
        assertEquals(1, routingEdgeRepository.count());
        assertEquals(NOW, routingMetaRepository.findById(RoutingMeta.LEGACY_IMPORT_DONE_MS).orElseThrow().valueMs);
    }

    @Test
    void legacyTableUntouched() throws Exception {
        legacyRow(DDDTLSUtil.publicKeyToName(tlsKey()), "clientA", "0");
        legacyRow(SecurityUtils.generateID(tlsKey().getEncoded()), "clientA", "17");
        legacyRow("junk", "clientB", "3");
        var before = legacyRows();
        assertEquals(3, before.size());

        importer.importOnce(NOW);
        assertEquals(before, legacyRows());
    }
}
