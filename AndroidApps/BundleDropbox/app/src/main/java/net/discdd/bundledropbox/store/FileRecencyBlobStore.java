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
 *   <li>{@link #offer} accepts only a parseable response with status SUCCESS and a
 *       {@code blobTimestamp} strictly greater than the stored one. A transport that has not synced
 *       with the server yet serves the default instance (status UNDEFINED, R2 §7); that, a FAILED
 *       blob, or an older blob from a staler carrier never replaces a good one.</li>
 *   <li>The file is replaced atomically ({@link AtomicFiles#write}); a torn file would block hosting.</li>
 * </ul>
 * The dropbox does not verify the signature (it may not hold the server key); the client does.
 * The 255-char TXT limit on the Base64 form is enforced by the Wi-Fi host, not here.
 *
 * <p>FUTURE: a blob whose timestamp is far ahead of real time (server clock jump) would win forever
 * and clients reject blobs more than 60 s in the future; a sanity bound could be added once the
 * dropbox has a trustworthy clock. FUTURE: a server-issued dropbox-bound blob relayed in control ADUs
 * (R3 option d2) would also be offered through here.
 *
 * <p>Threading: all methods are {@code synchronized}. Pure Java, JVM-testable.
 */
public final class FileRecencyBlobStore implements RecencyBlobStore {
    private static final Logger logger = Logger.getLogger(FileRecencyBlobStore.class.getName());

    private final Path file;
    /** Raw bytes of the accepted blob, or null. Never handed out without copying. */
    private byte[] currentRaw;
    /** Parsed form of {@link #currentRaw}, or null. Protobuf messages are immutable. */
    private GetRecencyBlobResponse currentParsed;

    /**
     * Opens the store at {@code paths.recencyBlobFile}, loading a previously accepted blob if the file
     * exists and is still a valid SUCCESS response.
     *
     * @param paths dropbox layout
     */
    public FileRecencyBlobStore(DropboxPaths paths) {
        this(paths.recencyBlobFile);
    }

    /**
     * Opens the store at an explicit file (tests).
     *
     * @param file blob file; created on the first accepted {@link #offer}
     */
    public FileRecencyBlobStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
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
        GetRecencyBlobResponse parsed = parseAcceptable(rawResponse);
        if (parsed == null) return false;
        long ts = parsed.getRecencyBlob().getBlobTimestamp();
        // FUTURE: bound far-future timestamps once the dropbox has a trustworthy clock.
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
    public synchronized long currentTimestamp() {
        return currentParsed == null ? 0L : currentParsed.getRecencyBlob().getBlobTimestamp();
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * @return the parsed response if it is one the client would accept status-wise (SUCCESS with an
     *         embedded blob), else null
     */
    private static GetRecencyBlobResponse parseAcceptable(byte[] raw) {
        GetRecencyBlobResponse r;
        try {
            r = GetRecencyBlobResponse.parseFrom(raw);
        } catch (InvalidProtocolBufferException e) {
            logger.log(Level.WARNING, "Recency blob does not parse; ignored");
            return null;
        }
        // The stock client throws RecencyException("Recency request failed") for anything but SUCCESS
        // (ClientBundleTransmission.processRecencyBlob, Research/R3 §2).
        if (r.getStatus() != RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS) {
            logger.log(Level.FINE, "Recency blob status " + r.getStatus() + "; ignored");
            return null;
        }
        if (!r.hasRecencyBlob()) {
            logger.log(Level.WARNING, "SUCCESS recency response without a blob; ignored");
            return null;
        }
        return r;
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
        GetRecencyBlobResponse parsed = parseAcceptable(raw);
        if (parsed == null) {
            logger.log(Level.WARNING, "Stored recency blob is unusable; treating as none");
            return;
        }
        currentRaw = raw;
        currentParsed = parsed;
    }
}
