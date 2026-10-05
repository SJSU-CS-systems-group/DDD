package net.discdd.bundledropbox.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Small file helpers shared by the store implementations: crash-safe "write whole file" and
 * "move into place".
 *
 * <p>The dropbox runs unattended for days and may lose power or be killed by Android at any time, so
 * every metadata file ({@code recencyBlob.bin}, {@code forwarded.json}) is replaced with the classic
 * write-temp, fsync, rename sequence. A reader therefore sees either the old or the new content, never
 * a truncated file. In particular a truncated {@code recencyBlob.bin} would make the dropbox unable to
 * host at all (the stock client aborts without a SUCCESS recency blob, Research/R3 §2).
 *
 * <p>Every rename is followed by an fsync of the destination directory ({@link #fsyncDirectory}): fsyncing
 * the file makes its bytes durable, but the new directory entry is only durable once the directory itself is
 * flushed. Without it a power cut can roll the rename back, losing an upload already acknowledged SUCCESS
 * or a newer recency blob (Audit m8).
 *
 * <p>Public so control bookkeeping can use the same durable replacement path as store metadata.
 */
public final class AtomicFiles {
    private static final Logger logger = Logger.getLogger(AtomicFiles.class.getName());

    private AtomicFiles() {}

    /**
     * Atomically replaces {@code target} with {@code data}. The temp file is created next to the
     * target (same directory, therefore same filesystem) so the final rename is atomic.
     *
     * @param target file to create or replace; its parent directory is created if missing
     * @param data   full new content
     * @throws IOException if the content could not be written or moved into place; the old file is
     *                     then left untouched
     */
    public static void write(Path target, byte[] data) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buf = ByteBuffer.wrap(data);
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ch.force(true);
            }
            moveReplacing(tmp, target);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(tmp);
            throw e;
        }
    }

    /**
     * Renames {@code source} to {@code target}, replacing any existing target, atomically when the
     * filesystem allows it. Both paths are always inside the dropbox root (one filesystem), so the
     * non-atomic fallback is only a safety net for exotic storage.
     */
    static void moveReplacing(Path source, Path target) throws IOException {
        try {
            // On POSIX, ATOMIC_MOVE is rename(2), which replaces an existing target atomically.
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            logger.log(Level.WARNING, "Atomic move not supported, falling back to plain replace: " + target);
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        fsyncDirectory(target.toAbsolutePath().getParent());
    }

    /**
     * Makes directory-entry changes (a rename into {@code dir}) durable. Best effort: on Linux/Android a
     * directory opens read-only and {@code fsync} works; platforms that refuse to open a directory throw, and
     * that is ignored because the rename itself already happened.
     *
     * @param dir the directory to flush; null is ignored
     */
    static void fsyncDirectory(Path dir) {
        if (dir == null) return;
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException | RuntimeException e) {
            logger.log(Level.FINE, "Directory fsync not supported for " + dir + ": " + e);
        }
    }

    /**
     * Flushes a file's content to the storage device. Called on staged bundles right before they are
     * renamed into a queue, so a power cut cannot leave a committed, zero-length bundle behind.
     */
    static void fsync(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
    }

    /** Deletes a file if present, logging (never throwing) on failure. */
    static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not delete " + p, e);
        }
    }
}
