package net.discdd.bundledropbox.store;

import com.google.protobuf.InvalidProtocolBufferException;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.RecencyBlobStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistent {@link RecencyBlobStore} keeping the newest server-signed recency blob at
 * {@link DropboxPaths#recencyBlobFile} ({@code Dropbox/meta/recencyBlob.bin}).
 *
 * <p>Role in the dropbox: the stock client aborts the whole exchange (no upload, no download) unless
 * {@code getRecencyBlob} returns status {@code RECENCY_BLOB_STATUS_SUCCESS} with a valid server
 * signature, and it only lists/exchanges with a peer whose TXT {@code recencyBlob} timestamp is newer
 * than its last exchange (Research/R3 §1-§2). The offline dropbox cannot mint blobs, so it re-serves
 * the newest genuine blob copied from a carrier (R3 option (c)). The cycle controller also uses
 * {@link #current()} being empty as the "bootstrap: VISIT only, do not HOST" condition.
 *
 * <p>Rules, matching the {@link RecencyBlobStore} contract:
 * <ul>
 *   <li>Bytes are stored and returned exactly as received. The signature covers the inner
 *       {@code RecencyBlob} bytes, so the dropbox must never rebuild or re-sign the message.</li>
 *   <li>{@link #offer} accepts only a parseable response that the injected {@link RecencyBlobVerifier}
 *       passes (production: {@link ServerTrust}, which mirrors the stock client's
 *       {@code processRecencyBlob}: SUCCESS, timestamp at most 60 s ahead, the pinned server key, a valid
 *       Curve signature) and whose {@code blobTimestamp} is strictly greater than the stored one. A
 *       transport that has not synced yet serves the default instance (status UNDEFINED, R2 §7); that, a
 *       FAILED blob, a blob from another deployment's server, a forged or far-future blob, or an older blob
 *       from a staler carrier never replaces a good one (Audit M5/M6).</li>
 *   <li>The stored blob is re-verified on load, so a blob accepted by an older build without these checks
 *       (or one that no longer matches a changed server key) is dropped at startup instead of locking out
 *       clients. {@link #reset()} is the operator's manual recovery.</li>
 *   <li>The file is replaced atomically ({@link AtomicFiles#write}); a torn file would block hosting.</li>
 * </ul>
 * The 255-char TXT limit on the Base64 form is enforced by the Wi-Fi host, not here.
 *
 * <p>FUTURE: a server-issued dropbox-bound blob relayed in control ADUs (R3 option d2) would also be
 * offered through here.
 *
 * <p>Threading: all methods are {@code synchronized}. Pure Java, JVM-testable.
 */
public final class FileRecencyBlobStore implements RecencyBlobStore {
    private static final Logger logger = Logger.getLogger(FileRecencyBlobStore.class.getName());

    private final Path file;
    private RecencyBlobVerifier verifier;
    /** Raw bytes of the accepted blob, or null. Never handed out without copying. */
    private byte[] currentRaw;
    /** Parsed form of {@link #currentRaw}, or null. Protobuf messages are immutable. */
    private GetRecencyBlobResponse currentParsed;

    /**
     * Opens the store at {@code paths.recencyBlobFile}, loading a previously accepted blob if the file
     * exists and still passes {@code verifier}.
     *
     * @param paths    dropbox layout
     * @param verifier trust check; production passes a {@link ServerTrust} built from the server identity key
     */
    public FileRecencyBlobStore(DropboxPaths paths, RecencyBlobVerifier verifier) {
        this(paths.recencyBlobFile, verifier);
    }

    /**
     * Opens the store at an explicit file (tests).
     *
     * @param file     blob file; created on the first accepted {@link #offer}
     * @param verifier trust check
     */
    public FileRecencyBlobStore(Path file, RecencyBlobVerifier verifier) {
        this.file = Objects.requireNonNull(file, "file");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        load();
    }

    @Override
    public synchronized Optional<byte[]> current() {
        return currentRaw == null ? Optional.empty() : Optional.of(currentRaw.clone());
    }

    /**
     * Parsed view of {@link #current()}, for the RPC server's {@code getRecencyBlob} and for display.
     * Not part of the {@link RecencyBlobStore} contract.
     *
     * @return the accepted response, or empty if none
     */
    public synchronized Optional<GetRecencyBlobResponse> currentResponse() {
        return Optional.ofNullable(currentParsed);
    }

    @Override
    public synchronized boolean offer(byte[] rawResponse) {
        if (rawResponse == null || rawResponse.length == 0) return false;
        GetRecencyBlobResponse parsed = parse(rawResponse);
        if (parsed == null) return false;
        String rejected = verifier.rejectionReason(parsed);
        if (rejected != null) {
            // A carrier that never synced (UNDEFINED) is routine; anything else means a foreign or bad blob.
            Level level = parsed.getStatus() == RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS ? Level.WARNING
                                                                                               : Level.FINE;
            logger.log(level, "Recency blob rejected: " + rejected);
            return false;
        }
        long ts = parsed.getRecencyBlob().getBlobTimestamp();
        if (ts <= currentTimestamp()) {
            // Equal timestamps are the common case (same carrier, no server sync in between).
            logger.log(Level.FINE, "Recency blob not newer (" + ts + " <= " + currentTimestamp() + "), ignored");
            return false;
        }
        byte[] copy = rawResponse.clone();
        try {
            AtomicFiles.write(file, copy);
        } catch (IOException e) {
            // Contract: true means "stored". Keep serving the old blob rather than an unpersisted one.
            logger.log(Level.WARNING, "Could not persist recency blob " + file, e);
            return false;
        }
        currentRaw = copy;
        currentParsed = parsed;
        logger.log(Level.INFO, "Accepted recency blob with timestamp " + ts);
        return true;
    }

    @Override
    public synchronized boolean isTrusted(byte[] rawResponse) {
        if (rawResponse == null || rawResponse.length == 0) return false;
        GetRecencyBlobResponse parsed = parse(rawResponse);
        return parsed != null && verifier.rejectionReason(parsed) == null;
    }

    @Override
    public synchronized long currentTimestamp() {
        return currentParsed == null ? 0L : currentParsed.getRecencyBlob().getBlobTimestamp();
    }

    /**
     * Replaces the trust check, e.g. after the operator imports different server keys. The stored blob is
     * re-checked and dropped (file deleted) if the new verifier rejects it, so clients of the new server are
     * not served a blob they would refuse.
     *
     * @param newVerifier the new trust check
     * @return true if the stored blob (if any) is still valid
     */
    public synchronized boolean setVerifier(RecencyBlobVerifier newVerifier) {
        verifier = Objects.requireNonNull(newVerifier, "verifier");
        if (currentParsed == null) return true;
        String rejected = verifier.rejectionReason(currentParsed);
        if (rejected == null) return true;
        logger.log(Level.WARNING, "Stored recency blob no longer trusted (" + rejected + "); dropping it");
        reset();
        return false;
    }

    /**
     * Operator recovery ("reset recency blob"): forgets the stored blob and deletes its file. The dropbox is
     * then back in the bootstrap state (VISIT only) until a carrier supplies a trusted blob.
     */
    public synchronized void reset() {
        currentRaw = null;
        currentParsed = null;
        AtomicFiles.deleteQuietly(file);
    }

    // ---------------------------------------------------------------------------------------------

    /** @return the parsed response, or null if the bytes are not a {@code GetRecencyBlobResponse} */
    private static GetRecencyBlobResponse parse(byte[] raw) {
        try {
            return GetRecencyBlobResponse.parseFrom(raw);
        } catch (InvalidProtocolBufferException e) {
            logger.log(Level.WARNING, "Recency blob does not parse; ignored");
            return null;
        }
    }

    private void load() {
        byte[] raw;
        try {
            raw = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return; // never had a blob: bootstrap state
        } catch (IOException e) {
            logger.log(Level.WARNING, "Cannot read recency blob " + file, e);
            return;
        }
        GetRecencyBlobResponse parsed = parse(raw);
        String rejected = parsed == null ? "does not parse" : verifier.rejectionReason(parsed);
        if (rejected != null) {
            // Includes blobs stored by a build that did not verify them (Audit M6 recovery).
            logger.log(Level.WARNING, "Stored recency blob is unusable (" + rejected + "); treating as none");
            return;
        }
        currentRaw = raw;
        currentParsed = parsed;
    }
}
