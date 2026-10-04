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

import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.dropbox.control.BundleJarInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.WARNING;

/**
 * {@link DropboxRetentionPlanner.Facts} backed by the server's repositories, built for one report. The per-ID facts
 * (received upstream IDs, issued downstream IDs) are bulk-loaded up front so planning a 20,000-entry report does not
 * issue 20,000 single-row queries; per-client facts are cached on first use.
 * <p>
 * Bundle-ID decryption uses AES keys from a {@link BundleIdKeys} cache shared across plans. bundle-core's
 * {@code ServerSecurity.decryptBundleID} derives the key with 65,536 PBKDF2 rounds on every call, which made a plan
 * for a few hundred pending bundles take tens of seconds while the handler's monitor blocked every client's bundle
 * generation. The key depends only on the client's identity key and the server's, so one derivation per client is
 * enough.
 */
final class ServerRetentionFacts implements DropboxRetentionPlanner.Facts {
    private static final Logger logger = Logger.getLogger(ServerRetentionFacts.class.getName());
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
    private final BundleIdKeys bundleIdKeys;
    /** fingerprint → client ID from the server's own client key store; built on the first database miss */
    private Map<String, String> keyStoreIndex;

    ServerRetentionFacts(InventoryReport report,
                         List<InventorySighting> sightings,
                         ReceivedBundleRepository receivedBundleRepository,
                         BundleMetadataRepository bundleMetadataRepository,
                         ClientDownstreamAckRepository clientDownstreamAckRepository,
                         ServerSecurity serverSecurity,
                         BundleIdKeys bundleIdKeys) {
        this.receivedBundleRepository = receivedBundleRepository;
        this.clientDownstreamAckRepository = clientDownstreamAckRepository;
        this.serverSecurity = serverSecurity;
        this.bundleIdKeys = bundleIdKeys;
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

    /**
     * Maps a fingerprint through {@code received_bundle} first, then through the server's client key store. The
     * table only fills as bundles arrive after the dropbox feature was deployed, so a client that has not uploaded
     * since would otherwise stay "unknown" and its pending relayed bundles would expire after the unknown TTL.
     */
    @Override
    public String clientIdForKey(String clientKeyFingerprint) {
        return clientByKey.computeIfAbsent(clientKeyFingerprint, k -> {
            var fromTable = receivedBundleRepository.findFirstByClientKeyFingerprint(k).map(r -> r.clientId);
            return fromTable.isPresent() ? fromTable : Optional.ofNullable(keyStoreIndex().get(k));
        }).orElse(null);
    }

    /**
     * Fingerprints every {@code Clients/<clientId>/clientBase.pub} the server holds (bundle-core's
     * {@code ServerSecurity} layout), with the same function the dropbox and {@code recordReceivedBundle} use.
     */
    private Map<String, String> keyStoreIndex() {
        if (keyStoreIndex != null) return keyStoreIndex;
        keyStoreIndex = new HashMap<>();
        if (serverSecurity == null || serverSecurity.getClientRootPath() == null) return keyStoreIndex;
        try (var clients = Files.list(serverSecurity.getClientRootPath())) {
            clients.forEach(dir -> {
                Path pem = dir.resolve(SecurityUtils.CLIENT_BASE_KEY);
                try {
                    if (!Files.isRegularFile(pem)) return;
                    String fp = BundleJarInfo.clientKeyFingerprintFromPem(Files.readString(pem));
                    if (fp != null) keyStoreIndex.put(fp, dir.getFileName().toString());
                } catch (IOException e) {
                    logger.log(FINE, "Cannot read " + pem, e);
                }
            });
        } catch (IOException e) {
            logger.log(WARNING, "Cannot list the server's client keys", e);
        }
        return keyStoreIndex;
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
            var key = bundleIdKeys.keyFor(serverSecurity, clientId);
            if (key == null) return OptionalLong.empty();
            String plainId = BundleIdKeys.decrypt(key, upstreamBundleId);
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
