package net.discdd.server.bundlerouting;

import net.discdd.server.repository.ReceivedBundleRepository;
import net.discdd.server.repository.RoutingEdgeRepository;
import net.discdd.server.repository.entity.RoutingEdge;
import net.discdd.server.repository.entity.RoutingEdgeId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.WARNING;

/**
 * The server's routes: which clients a carrier (a transport or a dropbox, named by
 * {@code SecurityUtils.peerIdForTlsKey} of its TLS key) can reach, kept as {@link RoutingEdge} rows.
 * <p>
 * Writes run inside the RPC's transaction, where a repository exception would mark the whole transaction
 * rollback-only and lose the bundle. So they never throw: inputs are validated first, and a row is upserted with a
 * conditional touch followed, only if no row exists, by an insert. A client's UPLOADED rows are written only under
 * that client's receive lock in {@code ServerBundleTransmission}, so two inserts of the same key cannot race. No
 * deletes run here; {@link RoutingSweeper} removes old rows.
 * <p>
 * Times are parameters ({@code nowMs}) so callers and tests control the clock.
 */
@Service
public class BundleRouting {
    private static final Logger logger = Logger.getLogger(BundleRouting.class.getName());
    private static final Set<RoutingSource> DIRECT_SOURCES = Set.of(RoutingSource.UPLOADED, RoutingSource.REPORTED);

    private final RoutingEdgeRepository routingEdgeRepository;
    private final ReceivedBundleRepository receivedBundleRepository;
    private final long ttlMs;

    public BundleRouting(RoutingEdgeRepository routingEdgeRepository,
                         ReceivedBundleRepository receivedBundleRepository,
                         @Value("${routing.ttl:14d}") Duration ttl) {
        this.routingEdgeRepository = routingEdgeRepository;
        this.receivedBundleRepository = receivedBundleRepository;
        this.ttlMs = ttl.toMillis();
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
     * The clients to put on carrier {@code peerId}: every client with a fresh UPLOADED or REPORTED edge from it,
     * newest {@code lastSeenMs} first, then by client ID, so the result (and the carrier's deletion set) is
     * deterministic. Uncapped.
     */
    public List<String> clientsForCarrier(String peerId, long nowMs) {
        if (!validId(peerId)) return List.of();
        Map<String, Long> newest = new HashMap<>();
        for (RoutingEdge edge : routingEdgeRepository.findByIdPeerIdAndIdSourceInAndLastSeenMsGreaterThanEqual(
                peerId,
                DIRECT_SOURCES,
                nowMs - ttlMs)) {
            newest.merge(edge.id.clientId, edge.lastSeenMs, Math::max);
        }
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
