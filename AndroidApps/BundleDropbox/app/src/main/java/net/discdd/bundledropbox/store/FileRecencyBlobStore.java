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
 * Persistent {@link RecencyBlobStore} keeping the dropbox's newest own server-signed recency blob at
 * {@link DropboxPaths#recencyBlobFile} ({@code Dropbox/meta/recencyBlob.bin}).
 *
 * <p>Role in the dropbox: the stock client aborts the whole exchange (no upload, no download) unless
 * {@code getRecencyBlob} returns status {@code RECENCY_BLOB_STATUS_SUCCESS} with a valid server
 * signature, and it only lists/exchanges with a peer whose TXT {@code recencyBlob} timestamp is newer
 * than its last exchange (Research/R3 §1-§2). The offline dropbox cannot mint blobs, so the server mints one
 * for the dropbox's own TLS key and sends it back in a control {@code RecencyGrant} (09 Own Recency Blob
 * Design, R3 option (d2)). The cycle controller also uses {@link #current()} being empty as the
 * "bootstrap: VISIT only, do not HOST" condition.
 *
 * <p>Rules, matching the {@link RecencyBlobStore} contract:
 * <ul>
 *   <li>Bytes are stored and returned exactly as received. The signature covers the inner
 *       {@code RecencyBlob} bytes, so the dropbox must never rebuild or re-sign the message.</li>
 *   <li>{@link #offer} accepts only a parseable response that the injected {@link RecencyBlobVerifier}
 *       passes (production: {@link ServerTrust}, which mirrors the stock client's
 *       {@code processRecencyBlob}: SUCCESS, timestamp at most 60 s ahead, the pinned server key, a valid
 *       Curve signature), whose {@code senderId} equals the dropbox's own sender id (the server's
 *       {@code SecurityUtils.generateID} of the dropbox TLS public key), and whose {@code blobTimestamp} is
 *       strictly greater than the stored one. A carrier transport's blob names the carrier, so it never passes,
 *       however genuine; nor do a FAILED blob, a blob from another deployment's server, a forged or far-future
 *       blob, or an older grant (Audit M5/M6).</li>
 *   <li>The stored blob is re-verified on load, so a blob that no longer passes (a carrier blob stored by an
 *       older build that copied carriers' blobs, or one that no longer matches a changed server key) is treated
 *       as none at startup and the dropbox goes back to bootstrap. Its file is left in place until the next
 *       accepted grant replaces it, because a check that fails only for now (a clock still wrong at boot) must
 *       not destroy a good blob; nothing else reads the file. {@link #reset()} is the operator's manual
 *       recovery.</li>
 *   <li>{@link #setVerifier} swaps only the server check; the own-sender-id condition is fixed for the life of
 *       the store.</li>
 *   <li>The file is replaced atomically ({@link AtomicFiles#write}); a torn file would block hosting.</li>
 * </ul>
 * The 255-char TXT limit on the Base64 form is enforced by the Wi-Fi host, not here.
 *
 * <p>Threading: all methods are {@code synchronized}. Pure Java, JVM-testable.
 */
public final class FileRecencyBlobStore implements RecencyBlobStore {
    private static final Logger logger = Logger.getLogger(FileRecencyBlobStore.class.getName());

    private final Path file;
    private final String ownSenderId;
    private RecencyBlobVerifier verifier;
    /** Raw bytes of the accepted blob, or null. Never handed out without copying. */
    private byte[] currentRaw;
    /** Parsed form of {@link #currentRaw}, or null. Protobuf messages are immutable. */
    private GetRecencyBlobResponse currentParsed;

    /**
     * Opens the store at {@code paths.recencyBlobFile}, loading a previously accepted blob if the file
     * exists and still passes {@code verifier} and the own-sender-id check.
     *
     * @param paths       dropbox layout
     * @param verifier    trust check; production passes a {@link ServerTrust} built from the server identity key
     * @param ownSenderId the dropbox's own sender id ({@code RecencyRequestSigner.senderIdFor} of its TLS public
     *                    key); only blobs minted for it are kept
     */
    public FileRecencyBlobStore(DropboxPaths paths, RecencyBlobVerifier verifier, String ownSenderId) {
        this(paths.recencyBlobFile, verifier, ownSenderId);
    }

    /**
     * Opens the store at an explicit file (tests).
     *
     * @param file        blob file; created on the first accepted {@link #offer}
     * @param verifier    trust check
     * @param ownSenderId the dropbox's own sender id
     */
    public FileRecencyBlobStore(Path file, RecencyBlobVerifier verifier, String ownSenderId) {
        this.file = Objects.requireNonNull(file, "file");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.ownSenderId = Objects.requireNonNull(ownSenderId, "ownSenderId");
        if (ownSenderId.isEmpty()) throw new IllegalArgumentException("ownSenderId is empty");
        load();
    }

    /** @return the sender id every stored blob must carry (the dropbox's own) */
    public String ownSenderId() {
        return ownSenderId;
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
        String rejected = rejectionReason(parsed);
        if (rejected != null) {
            logger.log(Level.WARNING, "Recency blob rejected: " + rejected);
            return false;
        }
        long ts = parsed.getRecencyBlob().getBlobTimestamp();
        if (ts <= currentTimestamp()) {
            // A replayed or reordered grant; the newer blob stays.
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
        logger.log(Level.INFO, "Accepted own recency blob with timestamp " + ts);
        return true;
    }

    @Override
    public synchronized boolean isServerSigned(byte[] rawResponse) {
        if (rawResponse == null || rawResponse.length == 0) return false;
        GetRecencyBlobResponse parsed = parse(rawResponse);
        return parsed != null && verifier.rejectionReason(parsed) == null;
    }

    @Override
    public synchronized long currentTimestamp() {
        return currentParsed == null ? 0L : currentParsed.getRecencyBlob().getBlobTimestamp();
    }

    /**
     * Replaces the trust check, e.g. after the operator imports different server keys. The own-sender-id
     * condition stays. The stored blob is re-checked and dropped (file deleted) if it no longer passes, so
     * clients of the new server are not served a blob they would refuse.
     *
     * @param newVerifier the new trust check
     * @return true if the stored blob (if any) is still valid
     */
    public synchronized boolean setVerifier(RecencyBlobVerifier newVerifier) {
        verifier = Objects.requireNonNull(newVerifier, "verifier");
        if (currentParsed == null) return true;
        String rejected = rejectionReason(currentParsed);
        if (rejected == null) return true;
        logger.log(Level.WARNING, "Stored recency blob no longer trusted (" + rejected + "); dropping it");
        reset();
        return false;
    }

    /**
     * Operator recovery ("reset recency blob"), and the server-key change: forgets the stored blob and deletes
     * its file. The dropbox is then back in the bootstrap state (VISIT only) until a server grant supplies a new
     * blob.
     */
    public synchronized void reset() {
        currentRaw = null;
        currentParsed = null;
        AtomicFiles.deleteQuietly(file);
    }

    // ---------------------------------------------------------------------------------------------

    /** @return null if the blob may be stored (server check, then own sender id), else why not */
    private String rejectionReason(GetRecencyBlobResponse parsed) {
        String rejected = verifier.rejectionReason(parsed);
        if (rejected != null) return rejected;
        String senderId = parsed.getRecencyBlob().getSenderId();
        if (!ownSenderId.equals(senderId)) {
            return "minted for sender " + (senderId.isEmpty() ? "(none)" : senderId) + ", not for this dropbox (" +
                    ownSenderId + ")";
        }
        return null;
    }

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
        String rejected = parsed == null ? "does not parse" : rejectionReason(parsed);
        if (rejected != null) {
            // Includes a carrier's blob copied by an older build, and blobs stored without verification (M6).
            // The file stays until the next accepted grant replaces it: a check that fails only for now (a clock
            // still wrong at boot) must not destroy a good blob.
            logger.log(Level.WARNING, "Stored recency blob is unusable (" + rejected + "); treating as none");
            return;
        }
        currentRaw = raw;
        currentParsed = parsed;
    }
}
