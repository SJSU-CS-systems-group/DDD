package net.discdd.server.dropbox;

import net.discdd.bundlesecurity.BundleIDGenerator;
import net.discdd.bundlesecurity.ServerSecurity;
import net.discdd.dropbox.control.InventoryReport;
import net.discdd.server.dropbox.DropboxRetentionPlanner.Direction;
import net.discdd.server.dropbox.DropboxRetentionPlanner.IssuedBundle;
import net.discdd.server.repository.BundleMetadataRepository;
import net.discdd.server.repository.ClientDownstreamAckRepository;
import net.discdd.server.repository.ReceivedBundleRepository;
import net.discdd.server.repository.entity.InventorySighting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * {@link DropboxRetentionPlanner.Facts} backed by the server's repositories, built for one report. The per-ID facts
 * (received upstream IDs, issued downstream IDs) are bulk-loaded up front so planning a 20,000-entry report does not
 * issue 20,000 single-row queries; per-client facts are cached on first use.
 */
final class ServerRetentionFacts implements DropboxRetentionPlanner.Facts {
    private static final int LOOKUP_CHUNK = 1000;
    private final Set<String> receivedIds = new HashSet<>();
    private final Map<String, IssuedBundle> issued = new HashMap<>();
    private final Map<String, Long> firstSeen = new HashMap<>();
    private final Map<String, Optional<String>> clientByKey = new HashMap<>();
    private final Map<String, Long> maxReceived = new HashMap<>();
    private final Map<String, Long> acked = new HashMap<>();
    private final ReceivedBundleRepository receivedBundleRepository;
    private final ClientDownstreamAckRepository clientDownstreamAckRepository;
    /** null in CLI mode or when the server keys failed to load; then no upstream ID can be decrypted */
    private final ServerSecurity serverSecurity;

    ServerRetentionFacts(InventoryReport report,
                         List<InventorySighting> sightings,
                         ReceivedBundleRepository receivedBundleRepository,
                         BundleMetadataRepository bundleMetadataRepository,
                         ClientDownstreamAckRepository clientDownstreamAckRepository,
                         ServerSecurity serverSecurity) {
        this.receivedBundleRepository = receivedBundleRepository;
        this.clientDownstreamAckRepository = clientDownstreamAckRepository;
        this.serverSecurity = serverSecurity;
        var upstreamIds = report.getUpstream().stream().map(InventoryReport.UpstreamEntry::getBundleId).toList();
        for (var chunk : chunks(upstreamIds)) {
            receivedBundleRepository.findAllById(chunk).forEach(r -> receivedIds.add(r.encryptedBundleId));
        }
        var downstreamIds =
                report.getDownstream().stream().map(InventoryReport.DownstreamEntry::getBundleId).toList();
        for (var chunk : chunks(downstreamIds)) {
            bundleMetadataRepository.findAllById(chunk)
                    .forEach(m -> issued.put(m.encryptedBundleId, new IssuedBundle(m.clientId, m.bundleCounter)));
        }
        for (var s : sightings) firstSeen.put(sightingKey(s.id.getDirection(), s.id.getBundleId()), s.firstSeenMs);
    }

    /** splits IDs into bounded groups so each bulk lookup stays a modest IN (...) query */
    private static List<List<String>> chunks(List<String> ids) {
        var chunks = new ArrayList<List<String>>();
        for (int i = 0; i < ids.size(); i += LOOKUP_CHUNK) {
            chunks.add(ids.subList(i, Math.min(ids.size(), i + LOOKUP_CHUNK)));
        }
        return chunks;
    }

    static String sightingKey(String direction, String bundleId) {
        return direction + '/' + bundleId;
    }

    @Override
    public boolean wasReceived(String upstreamBundleId) {
        return receivedIds.contains(upstreamBundleId);
    }

    @Override
    public String clientIdForKey(String clientKeyFingerprint) {
        return clientByKey.computeIfAbsent(clientKeyFingerprint,
                                           k -> receivedBundleRepository.findFirstByClientKeyFingerprint(k)
                                                   .map(r -> r.clientId))
                .orElse(null);
    }

    /**
     * Decrypts the ID with the shared secret of {@code clientId}. Encryption is deterministic AES-CBC (zero IV,
     * Research R8 §3.1), so the dropbox's file name decrypts exactly like the server's own copy would. A wrong key
     * usually fails the padding check; if it happens to pass, the embedded client ID will not match, so the result is
     * empty either way.
     */
    @Override
    public OptionalLong upstreamCounter(String upstreamBundleId, String clientId) {
        if (serverSecurity == null) return OptionalLong.empty();
        try {
            String plainId = serverSecurity.decryptBundleID(upstreamBundleId, clientId);
            byte[] embeddedClient = Base64.getUrlDecoder()
                    .decode(BundleIDGenerator.getClientIDFromBundleID(plainId, BundleIDGenerator.UPSTREAM));
            if (!Arrays.equals(embeddedClient, Base64.getUrlDecoder().decode(clientId))) return OptionalLong.empty();
            return OptionalLong.of(BundleIDGenerator.getCounterFromBundleID(plainId, BundleIDGenerator.UPSTREAM));
        } catch (Exception e) {
            return OptionalLong.empty();
        }
    }

    @Override
    public long maxReceivedCounter(String clientId) {
        return maxReceived.computeIfAbsent(clientId, c -> {
            Long max = receivedBundleRepository.findMaxBundleCounterByClientId(c);
            return max == null ? 0L : max;
        });
    }

    @Override
    public IssuedBundle issuedDownstream(String downstreamBundleId) {
        return issued.get(downstreamBundleId);
    }

    @Override
    public long ackedCounter(String clientId) {
        return acked.computeIfAbsent(clientId,
                                     c -> clientDownstreamAckRepository.findById(c).map(a -> a.ackedCounter).orElse(0L));
    }

    @Override
    public Long firstSeenMs(Direction direction, String bundleId) {
        return firstSeen.get(sightingKey(direction.name(), bundleId));
    }
}
