package net.discdd.bundledropbox.store;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Append-only {@link DeliveryLog} at {@code Dropbox/meta/delivery-log.jsonl}: one JSON object per line,
 * {@code {"bundleId":"...","servedAt":<epoch millis>}}, written each time a DOWNSTREAM bundle finished
 * streaming to a client ({@code psiDownloadBundle} or legacy {@code downloadBundle} completed).
 *
 * <p>v1 use: debugging and the Storage/Status UI ("served N times"). It deletes nothing and is not proof
 * of delivery: the client might still discard the bundle (e.g. not its newest, Research/R2 §6).
 *
 * <p>JSON Lines rather than one JSON document so recording is a cheap append that never rewrites
 * history; a torn last line after a crash is skipped by {@link #readAll()}. Because a torn line has no
 * trailing newline, the next append first terminates it ({@link #needsLineBreak}); otherwise the new record
 * would be glued onto the torn one and both lines would be lost (Audit i4).
 *
 * <p>FUTURE: basis for client-ACK report ADUs sent through the dropbox's own control endpoint, so the
 * server can issue keep/delete decisions ({@link RetentionPolicy}). FUTURE: rotate or compact the file
 * once reports are acknowledged by the server; v1 lets it grow (about 100 bytes per served bundle).
 *
 * <p>Threading: appends are {@code synchronized}. I/O failures are logged, never thrown, so a full disk
 * cannot break the RPC that served the bundle. Pure Java, JVM-testable.
 */
public final class FileDeliveryLog implements DeliveryLog {
    private static final Logger logger = Logger.getLogger(FileDeliveryLog.class.getName());

    /** File name inside {@link DropboxPaths#meta}. */
    public static final String FILE_NAME = "delivery-log.jsonl";

    private static final Gson GSON = new Gson();

    private final Path file;

    /** @param paths dropbox layout; the log lives at {@code paths.meta/delivery-log.jsonl} */
    public FileDeliveryLog(DropboxPaths paths) {
        this(paths.meta.resolve(FILE_NAME));
    }

    /** @param file explicit log file (tests); created on the first record */
    public FileDeliveryLog(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    @Override
    public synchronized void recordServed(String downstreamBundleId, long atMillis) {
        if (downstreamBundleId == null) return;
        // FUTURE: client-ACK reports: these events will feed report ADUs to the server's control endpoint.
        JsonObject o = new JsonObject();
        o.addProperty("bundleId", downstreamBundleId);
        o.addProperty("servedAt", atMillis);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            boolean lineBreakFirst = needsLineBreak(file);
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                                                            StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                if (lineBreakFirst) w.write('\n');
                w.write(GSON.toJson(o));
                w.write('\n');
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not append to delivery log " + file, e);
        }
    }

    /** @return true if {@code f} exists, is non-empty and does not end in a newline (a torn last line). */
    private static boolean needsLineBreak(Path f) throws IOException {
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
            long size = ch.size();
            if (size == 0) return false;
            ByteBuffer last = ByteBuffer.allocate(1);
            ch.read(last, size - 1);
            return last.get(0) != '\n';
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    /**
     * Reads the whole log, oldest first, skipping malformed lines (e.g. a line torn by a crash).
     *
     * @return all served events (unmodifiable); empty if the log does not exist or cannot be read
     */
    public synchronized List<Served> readAll() {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return Collections.emptyList();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not read delivery log " + file, e);
            return Collections.emptyList();
        }
        List<Served> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            try {
                JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                out.add(new Served(o.get("bundleId").getAsString(), o.get("servedAt").getAsLong()));
            } catch (JsonParseException | IllegalStateException | NullPointerException |
                     UnsupportedOperationException | NumberFormatException e) {
                logger.log(Level.FINE, "Skipping malformed delivery-log line");
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** One "bundle streamed to a client" event. Immutable. */
    public static final class Served {
        private final String bundleId;
        private final long servedAtMillis;

        /**
         * @param bundleId       encrypted DOWNSTREAM bundle id
         * @param servedAtMillis wall-clock time the stream completed
         */
        public Served(String bundleId, long servedAtMillis) {
            this.bundleId = Objects.requireNonNull(bundleId, "bundleId");
            this.servedAtMillis = servedAtMillis;
        }

        /** @return encrypted DOWNSTREAM bundle id. */
        public String bundleId() { return bundleId; }

        /** @return wall-clock time the stream completed, epoch millis. */
        public long servedAtMillis() { return servedAtMillis; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Served)) return false;
            Served s = (Served) o;
            return servedAtMillis == s.servedAtMillis && bundleId.equals(s.bundleId);
        }

        @Override
        public int hashCode() { return Objects.hash(bundleId, servedAtMillis); }

        @Override
        public String toString() { return "Served{" + bundleId + " at " + servedAtMillis + "}"; }
    }
}
