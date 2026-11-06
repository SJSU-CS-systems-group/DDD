package net.discdd.server.bundlerouting;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.discdd.server.repository.DropboxNodeRepository;
import net.discdd.server.repository.DropboxRecencyGrantRepository;
import net.discdd.server.repository.ReceivedBundleRepository;
import net.discdd.server.repository.RoutingEdgeRepository;
import net.discdd.server.repository.entity.RoutingEdge;
import net.discdd.server.repository.entity.RoutingEdgeId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.WARNING;

/**
 * The server's routes: which clients a carrier (a transport or a dropbox, named by
 * {@code SecurityUtils.peerIdForTlsKey} of its TLS key) can reach, kept as {@link RoutingEdge} rows.
 * <p>
 * Writes run inside the RPC's transaction, where a repository exception would mark the whole transaction
 * rollback-only and lose the bundle. So they never throw: inputs are validated first, and a row is upserted with a
 * conditional touch followed, only if no row exists, by an insert. A client's UPLOADED and REPORTED rows are written
 * only under that client's receive lock in {@code ServerBundleTransmission}, and DEPOSITED rows only under the
 * dropbox control handler's monitor, so two inserts of the same key cannot race. No deletes run here;
 * {@link RoutingSweeper} removes old rows.
 * <p>
 * Edges come from carriers uploading a client's bundles (UPLOADED), from the peers a client lists in its
 * {@code routing.metadata} (REPORTED, {@link #recordReported}), and from the client bundles a dropbox's inventory
 * lists (DEPOSITED, {@link #recordDeposited}). A carrier gets the clients it reaches directly plus, one hop only, the
 * clients known to have met a dropbox it reaches ({@link #clientsForCarrier}).
 * <p>
 * Times are parameters ({@code nowMs}) so callers and tests control the clock.
 */
@Service
public class BundleRouting {
    private static final Logger logger = Logger.getLogger(BundleRouting.class.getName());
    private static final Set<RoutingSource> DIRECT_SOURCES = Set.of(RoutingSource.UPLOADED, RoutingSource.REPORTED);
    /** name of the client's routing report inside a decrypted payload */
    public static final String ROUTING_METADATA_FILE = "routing.metadata";
    /** a larger {@code routing.metadata} is ignored */
    public static final int MAX_METADATA_BYTES = 16 * 1024;
    /** the key of a v2 report's generation time (client clock); a report without it is v1 and ignored */
    public static final String GEN_KEY = "@gen";
    /** at most this many reported peers are kept from one report, the newest first */
    public static final int MAX_REPORTED_PEERS = 32;
    /** values below this are legacy (v1) counts, not epoch milliseconds */
    static final long MIN_EPOCH_MS = 1_000_000_000_000L;
    /** a peer ID: {@code SecurityUtils.generateID}, padded Base64url of a SHA-1 */
    private static final Pattern PEER_ID = Pattern.compile("^[A-Za-z0-9_-]{27}=$");
    private static final ObjectMapper mapper = new ObjectMapper();

    private final RoutingEdgeRepository routingEdgeRepository;
    private final ReceivedBundleRepository receivedBundleRepository;
    private final DropboxNodeRepository dropboxNodeRepository;
    private final DropboxRecencyGrantRepository dropboxRecencyGrantRepository;
    private final long ttlMs;
    private final int maxRelayedClients;

    public BundleRouting(RoutingEdgeRepository routingEdgeRepository,
                         ReceivedBundleRepository receivedBundleRepository,
                         DropboxNodeRepository dropboxNodeRepository,
                         DropboxRecencyGrantRepository dropboxRecencyGrantRepository,
                         @Value("${routing.ttl:14d}") Duration ttl,
                         @Value("${routing.max-relayed-clients-per-carrier:64}") int maxRelayedClients) {
        this.routingEdgeRepository = routingEdgeRepository;
        this.receivedBundleRepository = receivedBundleRepository;
        this.dropboxNodeRepository = dropboxNodeRepository;
        this.dropboxRecencyGrantRepository = dropboxRecencyGrantRepository;
        this.ttlMs = ttl.toMillis();
        this.maxRelayedClients = Math.max(0, maxRelayedClients);
    }

    /** how long an edge stays usable after its {@code lastSeenMs} */
    public long ttlMs() {
        return ttlMs;
    }

    /** Carrier {@code peerId} uploaded a new bundle of {@code clientId} at {@code nowMs}. Never throws. */
    public void recordUploaded(String peerId, String clientId, long nowMs) {
        upsert(peerId, clientId, RoutingSource.UPLOADED, nowMs);
    }

    /**
     * Carrier {@code peerId} uploaded a bundle of {@code clientId} the server had already processed. This refreshes
     * the edge only if the server first received this bundle ({@code ReceivedBundle.receivedAtMs}) within the TTL,
     * so re-uploading an old jar cannot make a carrier a route. A bundle with no receipt row (one superseded before
     * it arrived) cannot be dated and refreshes nothing. Call it before the duplicate's receipt row is written.
     * Never throws.
     */
    public void recordUploadedDuplicate(String peerId, String clientId, String bundleId, long nowMs) {
        try {
            if (bundleId == null) return;
            var received = receivedBundleRepository.findById(bundleId);
            if (received.isEmpty() || received.get().receivedAtMs < nowMs - ttlMs) {
                logger.log(FINE, "Not refreshing route " + peerId + " -> " + clientId + ": duplicate " + bundleId +
                        " was first received outside the routing TTL");
                return;
            }
        } catch (RuntimeException e) {
            logger.log(WARNING, "Could not check duplicate " + bundleId + " for routing", e);
            return;
        }
        upsert(peerId, clientId, RoutingSource.UPLOADED, nowMs);
    }

    /**
     * Client {@code clientId}'s bundle, received at {@code receiptMs} (server clock), carried the
     * {@code routing.metadata} at {@code metadataFile}: a JSON map of peer ID to the client-clock time it last met
     * that peer, plus {@link #GEN_KEY}, the client-clock time the map was written. Each kept peer gets a REPORTED edge
     * with {@code lastSeen = receiptMs - max(0, gen - value)}: the entry's age relative to the report, so client clock
     * skew cancels.
     * <p>
     * Ignored: a missing, unparseable or oversized ({@link #MAX_METADATA_BYTES}) file, and a v1 report (no
     * {@link #GEN_KEY}). Entries are kept only if the key is a peer ID other than the client's own and the value is
     * epoch milliseconds (legacy counts are not); entries older than the TTL are skipped, and only the newest
     * {@link #MAX_REPORTED_PEERS} are recorded. Call it only for a decrypted, non-duplicate bundle, under the client's
     * receive lock. Never throws.
     */
    public void recordReported(String clientId, Path metadataFile, long receiptMs) {
        if (!validId(clientId)) return;
        Map<String, Long> report;
        try {
            report = readReport(metadataFile);
        } catch (IOException | RuntimeException e) {
            logger.log(FINE, "Ignoring unreadable routing metadata of " + clientId + ": " + e);
            return;
        }
        if (report == null) return;
        Long gen = report.get(GEN_KEY);
        if (gen == null) {
            logger.log(FINE, "Ignoring v1 routing metadata of " + clientId);
            return;
        }
        long oldest = receiptMs - ttlMs;
        report.entrySet()
                .stream()
                .filter(e -> e.getValue() != null && e.getValue() >= MIN_EPOCH_MS &&
                        PEER_ID.matcher(e.getKey()).matches() && !e.getKey().equals(clientId))
                .filter(e -> reportedLastSeen(receiptMs, gen, e.getValue()) >= oldest)
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                                .thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_REPORTED_PEERS)
                .forEach(e -> upsert(e.getKey(),
                                     clientId,
                                     RoutingSource.REPORTED,
                                     reportedLastSeen(receiptMs, gen, e.getValue())));
    }

    /**
     * Dropbox {@code dropboxId}'s accepted inventory lists a bundle of {@code clientId} that it first listed at
     * {@code firstSeenMs} (the {@code InventorySighting}, server clock): the client visited the dropbox then. This
     * DEPOSITED edge makes the client a via member of the dropbox even if it never reports its peers (an old client).
     * A sighting already older than the TTL at {@code nowMs}, and the dropbox's own ID, are ignored. Call it only from
     * the dropbox control handler, whose monitor serializes these writes. Never throws.
     */
    public void recordDeposited(String dropboxId, String clientId, long firstSeenMs, long nowMs) {
        if (firstSeenMs < nowMs - ttlMs || clientId == null || clientId.equals(dropboxId)) return;
        upsert(dropboxId, clientId, RoutingSource.DEPOSITED, firstSeenMs);
    }

    /** {@code receiptMs - max(0, gen - value)}; {@code value} is positive, so the subtraction cannot overflow */
    private static long reportedLastSeen(long receiptMs, long gen, long value) {
        return receiptMs - (gen > value ? gen - value : 0);
    }

    /** the report, or null if there is none or it is larger than {@link #MAX_METADATA_BYTES} */
    private static Map<String, Long> readReport(Path metadataFile) throws IOException {
        if (metadataFile == null || !Files.isRegularFile(metadataFile)) return null;
        byte[] bytes;
        try (var in = Files.newInputStream(metadataFile)) {
            bytes = in.readNBytes(MAX_METADATA_BYTES + 1);
        }
        if (bytes.length > MAX_METADATA_BYTES) {
            logger.log(FINE, "Ignoring routing metadata larger than " + MAX_METADATA_BYTES + " bytes");
            return null;
        }
        return mapper.readValue(bytes, new TypeReference<HashMap<String, Long>>() {});
    }

    /**
     * The clients to put on carrier {@code peerId}, as {@code direct ++ via}:
     * <ul>
     *   <li>direct: every client with a fresh UPLOADED or REPORTED edge from the carrier. Uncapped.</li>
     *   <li>via (one hop only): for each direct client that is a dropbox ({@code DropboxNode}), the clients with a
     *       fresh REPORTED edge to the dropbox's TLS peer ID (the {@code senderId} of its {@code DropboxRecencyGrant})
     *       or a fresh DEPOSITED edge from the dropbox; minus the direct clients and every dropbox; at most
     *       {@code routing.max-relayed-clients-per-carrier}.</li>
     * </ul>
     * Each part is ordered newest {@code lastSeenMs} first, then by client ID, so the result (and the carrier's
     * deletion set) is deterministic.
     */
    public List<String> clientsForCarrier(String peerId, long nowMs) {
        if (!validId(peerId)) return List.of();
        long freshFrom = nowMs - ttlMs;
        List<String> direct = newestFirst(routingEdgeRepository.findByIdPeerIdAndIdSourceInAndLastSeenMsGreaterThanEqual(
                peerId,
                DIRECT_SOURCES,
                freshFrom));
        if (direct.isEmpty() || maxRelayedClients == 0) return direct;
        List<String> drops = dropboxNodeRepository.findExistingClientIds(direct);
        if (drops.isEmpty()) return direct;

        Set<String> dropPeerIds = new HashSet<>();
        for (var grant : dropboxRecencyGrantRepository.findAllById(drops)) {
            if (validId(grant.senderId)) dropPeerIds.add(grant.senderId);
        }
        List<RoutingEdge> members = new ArrayList<>();
        if (!dropPeerIds.isEmpty()) {
            members.addAll(routingEdgeRepository.findByIdPeerIdInAndIdSourceAndLastSeenMsGreaterThanEqual(dropPeerIds,
                                                                                                          RoutingSource.REPORTED,
                                                                                                          freshFrom));
        }
        members.addAll(routingEdgeRepository.findByIdPeerIdInAndIdSourceAndLastSeenMsGreaterThanEqual(drops,
                                                                                                      RoutingSource.DEPOSITED,
                                                                                                      freshFrom));
        Set<String> directSet = new HashSet<>(direct);
        members.removeIf(e -> directSet.contains(e.id.clientId));
        if (members.isEmpty()) return direct;
        Set<String> memberDropboxes = new HashSet<>(dropboxNodeRepository.findExistingClientIds(members.stream()
                                                                                                         .map(e -> e.id.clientId)
                                                                                                         .distinct()
                                                                                                         .toList()));
        members.removeIf(e -> memberDropboxes.contains(e.id.clientId));

        List<String> result = new ArrayList<>(direct);
        newestFirst(members).stream().limit(maxRelayedClients).forEach(result::add);
        return result;
    }

    /** the edges' distinct clients, newest {@code lastSeenMs} first, then by client ID */
    private static List<String> newestFirst(Collection<RoutingEdge> edges) {
        Map<String, Long> newest = new HashMap<>();
        for (RoutingEdge edge : edges) newest.merge(edge.id.clientId, edge.lastSeenMs, Math::max);
        return newest.entrySet()
                .stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                                .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /** keeps {@code max(lastSeenMs, nowMs)} for the edge, creating it if needed; invalid input is ignored */
    private void upsert(String peerId, String clientId, RoutingSource source, long nowMs) {
        if (!validId(peerId) || !validId(clientId) || nowMs <= 0) {
            logger.log(FINE, "Ignoring route " + peerId + " -> " + clientId + " (" + source + ")");
            return;
        }
        try {
            if (routingEdgeRepository.touch(peerId, clientId, source, nowMs) > 0) return;
            var id = new RoutingEdgeId(peerId, clientId, source);
            if (routingEdgeRepository.existsById(id)) return; // already at least as new
            routingEdgeRepository.save(new RoutingEdge(id, nowMs));
        } catch (RuntimeException e) {
            // validation above should make this unreachable; the transaction may now be rollback-only
            logger.log(WARNING, "Could not record route " + peerId + " -> " + clientId + " (" + source + ")", e);
        }
    }

    private static boolean validId(String id) {
        return id != null && !id.isBlank() && id.length() <= RoutingEdgeId.MAX_ID_CHARS;
    }
}
