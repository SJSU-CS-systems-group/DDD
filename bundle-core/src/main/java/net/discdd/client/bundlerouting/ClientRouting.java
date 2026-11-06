package net.discdd.client.bundlerouting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.discdd.pathutils.ClientPaths;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

/**
 * The peers (carriers) this client has met over TLS, for the server's routing: a map of peer ID
 * ({@code SecurityUtils.peerIdForTlsKey}) to the client-clock epoch ms it last met that peer, kept in
 * {@code BundleRouting/routing.metadata} under one client root. One instance per {@code ClientBundleTransmission}.
 * <ul>
 *   <li>{@link #record} keeps the newest time in memory. Only a <em>material</em> change (a new peer, or a peer
 *       whose time in the file is at least {@link #REFRESH_MS} old) rewrites the file, atomically and as the whole
 *       map, so {@link #changedSince} (the file's mtime) changes at most once per peer per {@link #REFRESH_MS}, and
 *       a peer met more often still gets a fresh time into a bundle once a day.</li>
 *   <li>{@link #bundleMetaData} is what rides in each upstream bundle: entries younger than {@link #MAX_AGE_MS},
 *       the {@link #MAX_ENTRIES} newest, plus {@code "@gen": now} so the server can age entries relative to its own
 *       receipt time and knows this is the timestamp format (a v1 file of sighting counts has no {@code @gen}).</li>
 *   <li>Loading is tolerant: keys that are not peer IDs, legacy counts (values below {@link #MIN_EPOCH_MS}) and
 *       corrupt JSON are dropped, values more than {@link #FUTURE_SKEW_MS} ahead are clamped to now, and the file is
 *       rewritten once if anything was dropped or clamped.</li>
 * </ul>
 * Never throws for routing reasons: a failed write is logged and routing simply carries less.
 */
public class ClientRouting {
    private static final Logger logger = Logger.getLogger(ClientRouting.class.getName());

    /** a peer ID: SHA-1 (20 bytes) in padded base64url */
    static final Pattern PEER_ID = Pattern.compile("^[A-Za-z0-9_-]{27}=$");
    /** the key that carries the generation time in {@link #bundleMetaData}; never stored in the file */
    public static final String GEN_KEY = "@gen";
    static final int MAX_ENTRIES = 32;
    static final long MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;
    static final long REFRESH_MS = 24L * 60 * 60 * 1000;
    static final long FUTURE_SKEW_MS = 60L * 60 * 1000;
    /** anything smaller is not an epoch-ms timestamp (legacy files hold sighting counts) */
    static final long MIN_EPOCH_MS = 1_000_000_000_000L;

    private static final ObjectMapper mapper = new ObjectMapper();
    /** newest first, then by key, so the cap is deterministic */
    private static final Comparator<Map.Entry<String, Long>> NEWEST_FIRST =
            Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey());

    private final File metadataFile;
    private final Map<String, Long> peers = new HashMap<>();
    /** the map as last read from or written to the file; materiality is judged against it */
    private Map<String, Long> persisted = Map.of();

    public ClientRouting(ClientPaths clientPaths) {
        this(clientPaths, System.currentTimeMillis());
    }

    /** @param nowMs the time used to clamp future values while loading */
    ClientRouting(ClientPaths clientPaths, long nowMs) {
        this.metadataFile = clientPaths.metadataFile;
        if (load(nowMs)) persist();
    }

    /** @return true if the loaded map differs from the file (so it should be rewritten) */
    private boolean load(long nowMs) {
        if (!metadataFile.exists()) return false;
        Map<String, Long> stored;
        try {
            stored = mapper.readValue(metadataFile, new TypeReference<HashMap<String, Long>>() {});
        } catch (JsonProcessingException e) {
            logger.log(WARNING, "Corrupt " + metadataFile + ", starting with no routing peers: " + e.getMessage());
            return true;
        } catch (IOException e) {
            logger.log(WARNING, "Could not read " + metadataFile + ", starting with no routing peers", e);
            return false;
        }
        if (stored == null) return true;
        boolean changed = false;
        for (var entry : stored.entrySet()) {
            Long value = entry.getValue();
            if (!isPeerId(entry.getKey()) || value == null || value < MIN_EPOCH_MS) {
                changed = true;
                continue;
            }
            if (value > nowMs + FUTURE_SKEW_MS) {
                value = nowMs;
                changed = true;
            }
            peers.put(entry.getKey(), value);
        }
        if (changed) {
            logger.log(INFO, "Dropped or clamped invalid entries in " + metadataFile);
        } else {
            persisted = Map.copyOf(peers);
        }
        return changed;
    }

    static boolean isPeerId(String key) {
        return key != null && PEER_ID.matcher(key).matches();
    }

    /**
     * Records that this client met {@code peerId} at {@code nowMs}. The kept time never moves backwards.
     *
     * @return true if the change was material, so the file was rewritten (see the class comment)
     */
    public synchronized boolean record(String peerId, long nowMs) {
        if (!isPeerId(peerId)) {
            logger.log(WARNING, "Not recording invalid peer ID " + peerId);
            return false;
        }
        Long old = peers.get(peerId);
        peers.put(peerId, old == null ? nowMs : Math.max(old, nowMs));
        Long stored = persisted.get(peerId);
        boolean material = stored == null || nowMs - stored >= REFRESH_MS;
        if (material) persist();
        return material;
    }

    /** @return true if the routing file was rewritten after {@code timestampMs} (same mtime idiom as the ack file) */
    public synchronized boolean changedSince(long timestampMs) {
        return metadataFile.lastModified() > timestampMs;
    }

    /** {@link #bundleMetaData(long)} at the current time */
    public byte[] bundleMetaData() throws IOException {
        return bundleMetaData(System.currentTimeMillis());
    }

    /**
     * @return the {@code routing.metadata} JSON for a bundle generated at {@code nowMs}: the {@link #MAX_ENTRIES}
     *         newest peers younger than {@link #MAX_AGE_MS}, plus {@code "@gen": nowMs}. Still a
     *         {@code {String: Long}} map, so servers that parse the old format read it.
     */
    public synchronized byte[] bundleMetaData(long nowMs) throws IOException {
        var out = new LinkedHashMap<String, Long>();
        peers.entrySet()
                .stream()
                .filter(e -> nowMs - e.getValue() < MAX_AGE_MS)
                .sorted(NEWEST_FIRST)
                .limit(MAX_ENTRIES)
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        out.put(GEN_KEY, nowMs);
        return mapper.writeValueAsBytes(out);
    }

    /**
     * Writes the whole map to a temp file next to the routing file and moves it into place. On failure the file
     * keeps its old content, so the next {@link #record} of a changed peer tries again.
     */
    private void persist() {
        Path file = metadataFile.toPath().toAbsolutePath();
        Path tmp = null;
        try {
            Files.createDirectories(file.getParent());
            tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            mapper.writeValue(tmp.toFile(), new TreeMap<>(peers));
            try {
                Files.move(tmp, file, ATOMIC_MOVE, REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, REPLACE_EXISTING);
            }
            persisted = Map.copyOf(peers);
        } catch (IOException e) {
            logger.log(WARNING, "Could not write " + file, e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    logger.log(WARNING, "Could not delete " + tmp, e);
                }
            }
        }
    }
}
