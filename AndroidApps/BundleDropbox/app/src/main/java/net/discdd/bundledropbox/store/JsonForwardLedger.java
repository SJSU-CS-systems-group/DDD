package net.discdd.bundledropbox.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistent {@link ForwardLedger} stored as JSON at {@code Dropbox/meta/forwarded.json}.
 *
 * <p>Role in the dropbox: upstream bundles are kept until a server retention plan or the operator deletes
 * them, so each VISIT asks this ledger which upstream bundles the current carrier has
 * not acknowledged yet, and uploads only those. Each carrier is tracked separately because each one
 * reaches the server on its own schedule (Research/R2 §3).
 *
 * <p>File format (human-readable for debugging on the device):
 * <pre>
 * { "&lt;bundleId&gt;": { "&lt;carrierTransportId&gt;": &lt;forwardedAtMillis&gt;, ... }, ... }
 * </pre>
 *
 * <p>Durability: every {@link #recordForwarded} rewrites the whole file with write-temp/fsync/rename
 * ({@link AtomicFiles#write}), so a crash leaves the previous or the new version. The file is small
 * (one entry per upstream bundle, pruned by {@link #prune}). If writing fails the entry still counts
 * in memory; the only cost of a lost entry is one redundant upload after a restart, which the
 * transport overwrites harmlessly (same name, Research/R4 §4.1). A corrupt file is moved aside and the
 * ledger starts empty for the same reason.
 *
 * <p>Threading: all methods are {@code synchronized}; safe from the visit worker and the UI.
 *
 * <p>Uses Gson (already on the classpath through bundle-core); pure Java, JVM-testable.
 */
public final class JsonForwardLedger implements ForwardLedger {
    private static final Logger logger = Logger.getLogger(JsonForwardLedger.class.getName());

    /** File name inside {@link DropboxPaths#meta}. */
    public static final String FILE_NAME = "forwarded.json";

    private static final Type FILE_TYPE = new TypeToken<Map<String, Map<String, Long>>>() {}.getType();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    /** bundleId -> (carrierId -> forwardedAtMillis). Sorted maps keep the file diff-friendly. */
    private final Map<String, Map<String, Long>> entries = new TreeMap<>();

    /**
     * Opens the ledger at {@code paths.meta/forwarded.json}, loading it if present.
     *
     * @param paths dropbox layout
     */
    public JsonForwardLedger(DropboxPaths paths) {
        this(paths.meta.resolve(FILE_NAME));
    }

    /**
     * Opens the ledger at an explicit file (tests).
     *
     * @param file ledger file; created on the first {@link #recordForwarded}
     */
    public JsonForwardLedger(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        load();
    }

    @Override
    public synchronized boolean wasForwardedTo(String bundleId, String carrierId) {
        Map<String, Long> carriers = entries.get(bundleId);
        return carriers != null && carriers.containsKey(carrierId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Persists immediately. Recording the same pair again updates its time.
     */
    @Override
    public synchronized void recordForwarded(String bundleId, String carrierId, long atMillis) {
        Objects.requireNonNull(bundleId, "bundleId");
        Objects.requireNonNull(carrierId, "carrierId");
        entries.computeIfAbsent(bundleId, k -> new TreeMap<>()).put(carrierId, atMillis);
        persist();
    }

    /**
     * @param bundleId an upstream bundle id
     * @return carrierId -> forwardedAtMillis for that bundle (unmodifiable copy, empty if never forwarded);
     *         for the Storage tab
     */
    public synchronized Map<String, Long> carriersFor(String bundleId) {
        Map<String, Long> carriers = entries.get(bundleId);
        return carriers == null ? Collections.emptyMap() : Collections.unmodifiableMap(new TreeMap<>(carriers));
    }

    /** @return number of bundles with at least one forwarding record. */
    public synchronized int size() {
        return entries.size();
    }

    /**
     * Drops entries for bundles that no longer exist, so the ledger does not grow forever after the
     * operator clears storage or a server retention plan deletes bundles.
     *
     * @param bundleStillExists returns true for ids that should be kept
     * @return number of bundle entries removed
     */
    public synchronized int prune(Predicate<String> bundleStillExists) {
        int removed = 0;
        for (Iterator<String> it = entries.keySet().iterator(); it.hasNext(); ) {
            if (!bundleStillExists.test(it.next())) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) persist();
        return removed;
    }

    /**
     * Convenience for {@link #prune(Predicate)}: keeps only ids still committed in the store's
     * {@link Direction#UPSTREAM} queue (the only direction that is ever forwarded to carriers).
     *
     * @param store the relay store
     * @return number of bundle entries removed
     */
    public int pruneAgainst(RelayStore store) {
        return prune(id -> store.contains(Direction.UPSTREAM, id));
    }

    // ---------------------------------------------------------------------------------------------

    private void load() {
        String json;
        try {
            json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return; // first run
        } catch (IOException e) {
            logger.log(Level.WARNING, "Cannot read forward ledger " + file + "; starting empty", e);
            return;
        }
        try {
            Map<String, Map<String, Long>> loaded = GSON.fromJson(json, FILE_TYPE);
            if (loaded == null) return;
            for (Map.Entry<String, Map<String, Long>> e : loaded.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                Map<String, Long> carriers = new TreeMap<>();
                for (Map.Entry<String, Long> c : e.getValue().entrySet()) {
                    if (c.getKey() != null && c.getValue() != null) carriers.put(c.getKey(), c.getValue());
                }
                if (!carriers.isEmpty()) entries.put(e.getKey(), carriers);
            }
        } catch (JsonParseException | ClassCastException | IllegalStateException e) {
            // Losing the ledger only causes redundant (idempotent) uploads, so keep going.
            entries.clear();
            Path aside = file.resolveSibling(file.getFileName() + ".corrupt");
            logger.log(Level.WARNING, "Corrupt forward ledger; moved to " + aside + " and starting empty", e);
            try {
                AtomicFiles.moveReplacing(file, aside);
            } catch (IOException moveErr) {
                logger.log(Level.WARNING, "Could not move corrupt ledger aside", moveErr);
            }
        }
    }

    private void persist() {
        try {
            AtomicFiles.write(file, GSON.toJson(entries, FILE_TYPE).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not persist forward ledger " + file, e);
        }
    }
}
