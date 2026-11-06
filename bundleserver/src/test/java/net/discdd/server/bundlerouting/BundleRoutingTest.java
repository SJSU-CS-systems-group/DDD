package net.discdd.server.bundlerouting;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.server.repository.DropboxNodeRepository;
import net.discdd.server.repository.DropboxRecencyGrantRepository;
import net.discdd.server.repository.ReceivedBundleRepository;
import net.discdd.server.repository.RoutingEdgeRepository;
import net.discdd.server.repository.entity.DropboxNode;
import net.discdd.server.repository.entity.DropboxRecencyGrant;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2-backed tests of {@link BundleRouting} (UPLOADED and REPORTED writes, direct and one-hop via delivery sets) and
 * {@link RoutingSweeper} on a real {@link RoutingEdgeRepository}.
 */
@DataJpaTest
@Import({ GrpcExecutor.class })
class BundleRoutingTest {
    private static final Duration TTL = Duration.ofDays(14);
    private static final long NOW = 1_800_000_000_000L;
    private static final String T = "carrierT";
    private static final String C = "clientC";
    private static final int CAP = 64;
    private static final long HOUR = Duration.ofHours(1).toMillis();
    private static final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    static Path staticTempDir;
    @TempDir
    Path tempDir;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("bundle-server.bundle-store-root", () -> staticTempDir.toString());
    }

    @Autowired
    RoutingEdgeRepository routingEdgeRepository;
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

    private BundleRouting routing;

    @BeforeEach
    void setUp() {
        routing = routingWithCap(CAP);
    }

    private BundleRouting routingWithCap(int cap) {
        return new BundleRouting(routingEdgeRepository,
                                 receivedBundleRepository,
                                 dropboxNodeRepository,
                                 dropboxRecencyGrantRepository,
                                 TTL,
                                 cap);
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

    // ---------------------------------------------------------------- REPORTED

    /** a peer ID in the form clients report: {@code SecurityUtils.generateID}, 28 characters */
    private static String peer(String name) {
        return SecurityUtils.generateID(name.getBytes(StandardCharsets.UTF_8));
    }

    private Path metadata(Map<String, ?> report) throws Exception {
        return metadataFile(mapper.writeValueAsBytes(report));
    }

    private Path metadataFile(byte[] content) throws Exception {
        Path file = Files.createTempFile(tempDir, "routing", ".metadata");
        Files.write(file, content);
        return file;
    }

    /** the REPORTED edges of client {@code client}: peer to lastSeen, as the database holds them */
    private Map<String, Long> reported(String client) {
        entityManager.flush();
        entityManager.clear();
        var result = new HashMap<String, Long>();
        for (var edge : routingEdgeRepository.findAll()) {
            if (edge.id.source == RoutingSource.REPORTED && edge.id.clientId.equals(client)) {
                result.put(edge.id.peerId, edge.lastSeenMs);
            }
        }
        return result;
    }

    @Test
    void v1ReportWithoutGenIgnored() throws Exception {
        routing.recordReported(C, metadata(Map.of(peer("p1"), NOW - HOUR)), NOW);
        routing.recordReported(C, metadataFile("{}".getBytes()), NOW);
        routing.recordReported(C, metadataFile("null".getBytes()), NOW);
        routing.recordReported(C, tempDir.resolve("missing"), NOW);
        assertEquals(0, routingEdgeRepository.count());
    }

    /** a {@code @gen} that is not epoch ms (a count, non-numeric, null) cannot date entries: the report counts as v1 */
    @Test
    void genNotEpochMsTreatedAsV1() throws Exception {
        routing.recordReported(C, metadata(Map.of(BundleRouting.GEN_KEY, 7L, peer("p1"), NOW - HOUR)), NOW);
        routing.recordReported(C,
                               metadata(Map.of(BundleRouting.GEN_KEY,
                                               BundleRouting.MIN_EPOCH_MS - 1,
                                               peer("p2"),
                                               NOW - HOUR)),
                               NOW);
        routing.recordReported(C, metadata(Map.of(BundleRouting.GEN_KEY, "soon", peer("p3"), NOW - HOUR)), NOW);
        var nullGen = new HashMap<String, Long>();
        nullGen.put(BundleRouting.GEN_KEY, null);
        nullGen.put(peer("p4"), NOW - HOUR);
        routing.recordReported(C, metadata(nullGen), NOW);
        assertEquals(Map.of(), reported(C));

        routing.recordReported(C,
                               metadata(Map.of(BundleRouting.GEN_KEY,
                                               BundleRouting.MIN_EPOCH_MS,
                                               peer("p5"),
                                               BundleRouting.MIN_EPOCH_MS)),
                               NOW);
        assertEquals(Map.of(peer("p5"), NOW), reported(C));
    }

    @Test
    void relativeAgeCancelsClientSkew() throws Exception {
        // the client's clock runs 3 h ahead; it met p1 2 h before writing the report
        long gen = NOW + 3 * HOUR;
        routing.recordReported(C, metadata(Map.of(BundleRouting.GEN_KEY, gen, peer("p1"), gen - 2 * HOUR)), NOW);
        // an entry stamped after the report itself counts as met at receipt
        routing.recordReported(C, metadata(Map.of(BundleRouting.GEN_KEY, gen, peer("p2"), gen + HOUR)), NOW);
        assertEquals(Map.of(peer("p1"), NOW - 2 * HOUR, peer("p2"), NOW), reported(C));
    }

    @Test
    void rejectsBadKeysSelfKeyAndLegacyCounts() throws Exception {
        String self = peer("self");
        var report = new HashMap<String, Long>();
        report.put(BundleRouting.GEN_KEY, NOW);
        report.put(peer("good"), NOW);
        report.put("carrierT", NOW); // not a peer ID
        report.put(peer("unpadded").substring(0, 27), NOW);
        report.put(peer("plus").substring(0, 26) + "+=", NOW); // standard, not URL-safe, Base64
        report.put(self, NOW); // the client itself
        report.put(peer("legacyCount"), 7L); // a v1 count, not a time
        report.put(peer("justBelow"), BundleRouting.MIN_EPOCH_MS - 1);
        report.put(peer("nullValue"), null);
        routing.recordReported(self, metadata(report), NOW);
        assertEquals(Map.of(peer("good"), NOW), reported(self));
    }

    @Test
    void keepsNewest32() throws Exception {
        var report = new HashMap<String, Long>();
        report.put(BundleRouting.GEN_KEY, NOW);
        var byAge = new ArrayList<String>();
        for (int i = 0; i < 40; i++) {
            String p = peer("p" + i);
            byAge.add(p);
            report.put(p, NOW - i * 1000L);
        }
        // a tie at the boundary: the smaller key wins the last place
        String tieA = byAge.get(BundleRouting.MAX_REPORTED_PEERS - 1);
        String tieB = byAge.get(BundleRouting.MAX_REPORTED_PEERS);
        report.put(tieB, report.get(tieA));
        String winner = tieA.compareTo(tieB) < 0 ? tieA : tieB;
        String loser = winner.equals(tieA) ? tieB : tieA;

        routing.recordReported(C, metadata(report), NOW);
        var kept = reported(C);
        assertEquals(BundleRouting.MAX_REPORTED_PEERS, kept.size());
        assertEquals(Set.copyOf(byAge.subList(0, BundleRouting.MAX_REPORTED_PEERS - 1)),
                     kept.keySet().stream().filter(p -> !p.equals(winner)).collect(Collectors.toSet()));
        assertTrue(kept.containsKey(winner));
        assertFalse(kept.containsKey(loser));
    }

    @Test
    void entryOlderThanTtlSkipped() throws Exception {
        long gen = NOW - 5 * HOUR; // the report itself sat on carriers for 5 h; only the entries' ages count
        routing.recordReported(C, metadata(Map.of(BundleRouting.GEN_KEY, gen,
                                                  peer("tooOld"), gen - TTL.toMillis() - 1,
                                                  peer("atTtl"), gen - TTL.toMillis())), NOW);
        assertEquals(Map.of(peer("atTtl"), NOW - TTL.toMillis()), reported(C));
    }

    @Test
    void oversizeMetadataIgnored() throws Exception {
        byte[] json = mapper.writeValueAsBytes(Map.of(BundleRouting.GEN_KEY, NOW, peer("p1"), NOW));
        // JSON whitespace pads the same report to the limit and one byte past it
        byte[] atLimit = new byte[BundleRouting.MAX_METADATA_BYTES];
        Arrays.fill(atLimit, (byte) ' ');
        System.arraycopy(json, 0, atLimit, 0, json.length);
        byte[] overLimit = Arrays.copyOf(atLimit, atLimit.length + 1);
        overLimit[overLimit.length - 1] = ' ';

        routing.recordReported(C, metadataFile(overLimit), NOW);
        assertEquals(Map.of(), reported(C));
        routing.recordReported(C, metadataFile(atLimit), NOW);
        assertEquals(Map.of(peer("p1"), NOW), reported(C));
    }

    // ---------------------------------------------------------------- via

    private static final String D = "dropboxD";
    /** D's TLS peer ID: what clients that met D report */
    private static final String D_PEER = peer("dropboxD-tls");

    private void edge(String peerId, String client, RoutingSource source, long lastSeenMs) {
        routingEdgeRepository.save(new RoutingEdge(new RoutingEdgeId(peerId, client, source), lastSeenMs));
    }

    private void dropboxNode(String clientId) {
        dropboxNodeRepository.save(new DropboxNode(clientId, NOW));
    }

    private void grant(String clientId, String senderId) {
        var grant = new DropboxRecencyGrant(clientId, NOW);
        grant.senderId = senderId;
        dropboxRecencyGrantRepository.save(grant);
    }

    /** carrier T reaches dropbox D, which has a grant naming {@link #D_PEER} */
    private void carrierReachesDropbox() {
        dropboxNode(D);
        grant(D, D_PEER);
        routing.recordUploaded(T, D, NOW - 10);
    }

    @Test
    void viaIncludesReportedMembersOfDirectDropbox() {
        carrierReachesDropbox();
        edge(D_PEER, "reporter", RoutingSource.REPORTED, NOW - 5);
        edge(D, "depositor", RoutingSource.DEPOSITED, NOW - 7);
        // stale evidence of D does not count
        edge(D_PEER, "staleReporter", RoutingSource.REPORTED, NOW - TTL.toMillis() - 1);
        // members report D's TLS peer ID; a REPORTED edge naming D's client ID is not D's member
        edge(D, "misfiled", RoutingSource.REPORTED, NOW);
        assertEquals(List.of(D, "reporter", "depositor"), routing.clientsForCarrier(T, NOW));
    }

    @Test
    void viaRequiresDropboxNodeAndGrant() {
        // X has a grant but is not a dropbox
        grant("notDropbox", peer("x-tls"));
        routing.recordUploaded(T, "notDropbox", NOW);
        edge(peer("x-tls"), "reporterOfX", RoutingSource.REPORTED, NOW);
        edge("notDropbox", "depositorOfX", RoutingSource.DEPOSITED, NOW);
        // N is a dropbox without a grant: its DEPOSITED members count, nothing names its TLS peer ID
        dropboxNode("noGrant");
        routing.recordUploaded(T, "noGrant", NOW - 1);
        edge(peer("noGrant-tls"), "reporterOfN", RoutingSource.REPORTED, NOW);
        edge("noGrant", "depositorOfN", RoutingSource.DEPOSITED, NOW);
        assertEquals(List.of("notDropbox", "noGrant", "depositorOfN"), routing.clientsForCarrier(T, NOW));
    }

    @Test
    void viaExcludesDirectAndDropboxes() {
        carrierReachesDropbox();
        routing.recordUploaded(T, "directToo", NOW - 20);
        edge(D_PEER, "directToo", RoutingSource.REPORTED, NOW);
        // another dropbox that met D is not delivered through D's carrier
        dropboxNode("otherDropbox");
        edge(D_PEER, "otherDropbox", RoutingSource.REPORTED, NOW);
        edge(D_PEER, "member", RoutingSource.REPORTED, NOW);
        // nothing beyond one hop: a member of a dropbox reached only through D
        edge("otherDropbox", "twoHops", RoutingSource.DEPOSITED, NOW);
        assertEquals(List.of(D, "directToo", "member"), routing.clientsForCarrier(T, NOW));
    }

    @Test
    void viaCapDeterministic() {
        carrierReachesDropbox();
        edge(D_PEER, "m-b", RoutingSource.REPORTED, NOW - 1);
        edge(D_PEER, "m-a", RoutingSource.REPORTED, NOW - 1);
        edge(D, "m-c", RoutingSource.DEPOSITED, NOW);
        edge(D_PEER, "m-d", RoutingSource.REPORTED, NOW - 2);
        // a client with both kinds of evidence is ranked by its newest
        edge(D_PEER, "m-e", RoutingSource.REPORTED, NOW - 3);
        edge(D, "m-e", RoutingSource.DEPOSITED, NOW - 1);
        var capped = routingWithCap(3);
        var expected = List.of(D, "m-c", "m-a", "m-b");
        assertEquals(expected, capped.clientsForCarrier(T, NOW));
        assertEquals(expected, capped.clientsForCarrier(T, NOW), "the same inputs give the same list");
        assertEquals(List.of(D, "m-c", "m-a", "m-b", "m-e", "m-d"), routing.clientsForCarrier(T, NOW));
        assertEquals(List.of(D), routingWithCap(0).clientsForCarrier(T, NOW), "a cap of 0 turns via off");
    }

    // ---------------------------------------------------------------- DEPOSITED

    private Map<String, Long> deposited(String dropboxId) {
        entityManager.flush();
        entityManager.clear();
        var result = new HashMap<String, Long>();
        for (var edge : routingEdgeRepository.findAll()) {
            if (edge.id.source == RoutingSource.DEPOSITED && edge.id.peerId.equals(dropboxId)) {
                result.put(edge.id.clientId, edge.lastSeenMs);
            }
        }
        return result;
    }

    @Test
    void depositedMembersCountForVia() {
        // D has no grant, so no REPORTED edge can name it: its deposits alone make clients its members
        dropboxNode(D);
        routing.recordUploaded(T, D, NOW - 10);
        routing.recordDeposited(D, "oldClient", NOW - HOUR, NOW);
        routing.recordDeposited(D, "staleDeposit", NOW - TTL.toMillis() - 1, NOW);
        routing.recordDeposited(D, D, NOW, NOW); // the dropbox's own bundle
        routing.recordDeposited(D, null, NOW, NOW);
        assertEquals(List.of(D, "oldClient"), routing.clientsForCarrier(T, NOW));
        assertEquals(Map.of("oldClient", NOW - HOUR), deposited(D), "stale and self deposits are not written");
        // a deposit at a dropbox the carrier does not reach routes nothing
        routing.recordDeposited("elsewhere", "farClient", NOW, NOW);
        assertEquals(List.of(D, "oldClient"), routing.clientsForCarrier(T, NOW));
    }

    @Test
    void depositedLastSeenIsSightingFirstSeen() {
        routing.recordDeposited(D, C, NOW - 5 * HOUR, NOW);
        assertEquals(Map.of(C, NOW - 5 * HOUR), deposited(D), "dated by the sighting, not by the report");
        routing.recordDeposited(D, C, NOW - 2 * HOUR, NOW + HOUR);
        assertEquals(Map.of(C, NOW - 2 * HOUR), deposited(D), "a newer sighting raises lastSeen");
        routing.recordDeposited(D, C, NOW - 9 * HOUR, NOW + 2 * HOUR);
        assertEquals(Map.of(C, NOW - 2 * HOUR), deposited(D), "an older sighting never lowers it");
    }
}
