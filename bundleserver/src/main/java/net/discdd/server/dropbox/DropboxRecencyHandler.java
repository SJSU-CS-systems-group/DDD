package net.discdd.server.dropbox;

import net.discdd.bundlesecurity.ServerSecurity;
import net.discdd.dropbox.control.DropboxControl;
import net.discdd.dropbox.control.DropboxControlCodec;
import net.discdd.dropbox.control.DropboxControlException;
import net.discdd.dropbox.control.RecencyGrant;
import net.discdd.dropbox.control.RecencyRequest;
import net.discdd.dropbox.control.RecencyRequestSigner;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.model.ADU;
import net.discdd.server.applicationdatamanager.AduStores;
import net.discdd.server.applicationdatamanager.InternalAppHandler;
import net.discdd.server.bundlesecurity.RecencyBlobMinter;
import net.discdd.server.repository.DropboxRecencyGrantRepository;
import net.discdd.server.repository.entity.DropboxRecencyGrant;
import net.discdd.utils.StoreADUs;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.whispersystems.libsignal.InvalidKeyException;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

/**
 * The server end of the dropbox recency channel ({@link DropboxControl#RECENCY_APP_ID}). A DDD Dropbox gets its
 * <em>own</em> server-signed recency blob this way, through the carrier relay, instead of re-serving a carrier's
 * ("09 Own Recency Blob Design" § Server). Its embedded client endpoint uploads a {@link RecencyRequest} signed with
 * the dropbox's TLS key; this handler answers with a {@link RecencyGrant} ADU in that endpoint's send store, so the
 * grant rides the dropbox's next downstream bundle. The blob is minted by {@link RecencyBlobMinter}, the same routine
 * as the gRPC {@code getRecencyBlob}, for {@code senderId = RecencyRequestSigner.senderIdFor(tlsPublicKey)}: the ID
 * that RPC would give the dropbox's TLS certificate, which the dropbox presents to its clients.
 * <p>
 * Flow for one delivery: decode every ADU and keep the newest request (highest {@code requestSeq}) whose signature
 * {@link RecencyRequestSigner#verify verifies} for the client ID whose bundle carried it; malformed, unknown,
 * misrouted or forged ADUs are logged and dropped (consumed). Then mint a blob, replace any queued grant with the new
 * one, and upsert {@link DropboxRecencyGrant}. A request whose {@code requestSeq} and key match the grant still
 * queued is a re-sent copy and is ignored, so the dropbox's next bundle is not churned. Any other request is answered,
 * including one numbered below an earlier grant: a dropbox that lost its request state restarts its numbering, and
 * the receive store already skips re-delivered ADU IDs.
 * <p>
 * Who may ask: any client, as any TLS peer may call the gRPC {@code getRecencyBlob}; the signature means no one gets
 * a blob for a key it does not hold.
 * <p>
 * Dependencies are limited to a repository, {@link AduStores} and an {@code ObjectProvider<ServerSecurity>} (see
 * {@link InternalAppHandler} for the Spring cycle this avoids). Without a {@code ServerSecurity} (CLI mode, or keys
 * that failed to load) nothing can be signed, so the handler throws and leaves the ADUs for the live server.
 * <p>
 * Transactions: on a live server the {@link DropboxRecencyGrant} row commits with the upload RPC ({@code GrpcExecutor})
 * while the grant ADU is written to the send store at once, as for {@code DropboxControlHandler}'s plans. So the
 * duplicate check reads the queued grant itself, never the row, and {@link #isRegisteredFor} also counts a queued
 * grant: if the RPC rolled back after this handler returned, the receive store has already consumed the request (a
 * re-upload of it is skipped), and the grant still queued must reach the dropbox, whose request is outstanding.
 */
@Component
public class DropboxRecencyHandler implements InternalAppHandler {
    private static final Logger logger = Logger.getLogger(DropboxRecencyHandler.class.getName());

    private final DropboxRecencyGrantRepository recencyGrantRepository;
    private final ObjectProvider<ServerSecurity> serverSecurityProvider;
    private final StoreADUs sendFolder;

    public DropboxRecencyHandler(DropboxRecencyGrantRepository recencyGrantRepository,
                                 ObjectProvider<ServerSecurity> serverSecurityProvider,
                                 AduStores aduStores) {
        this.recencyGrantRepository = recencyGrantRepository;
        this.serverSecurityProvider = serverSecurityProvider;
        this.sendFolder = aduStores.getSendADUsStorage();
    }

    @Override
    public String appId() {
        return DropboxControl.RECENCY_APP_ID;
    }

    /**
     * Only clients that asked for a blob get the recency app ID, so a brand-new requester's first grant is in its next
     * bundle; a stock client's app ID list is unchanged (and its send store is not touched by this check).
     */
    @Override
    public boolean isRegisteredFor(String clientId) {
        return clientId != null && (recencyGrantRepository.existsById(clientId) || grantQueued(clientId));
    }

    @Override
    public synchronized void handleUpstream(String clientId, List<ADU> adus) throws IOException {
        var serverSecurity = serverSecurityProvider.getIfAvailable();
        if (serverSecurity == null) {
            // the ADUs stay in the receive store and are handled by the live server (class Javadoc)
            throw new IOException("server keys unavailable; leaving recency ADUs from " + clientId + " in place");
        }
        // An endpoint re-sends unacknowledged ADUs, so one delivery can hold several requests: keep the newest.
        RecencyRequest newest = null;
        for (var adu : adus) {
            byte[] bytes = Files.readAllBytes(adu.getSource().toPath());
            try {
                var request = DropboxControlCodec.decodeRecencyRequest(bytes);
                if (!RecencyRequestSigner.verify(clientId, request)) {
                    logger.log(WARNING,
                               "Dropping recency request " + request.getRequestSeq() + " (ADU " + adu.getADUId() +
                                       ") from " + clientId + ": signature does not verify for this client");
                    continue;
                }
                if (newest == null || request.getRequestSeq() > newest.getRequestSeq()) newest = request;
            } catch (DropboxControlException e) {
                // the ADU is consumed either way: a bad message will not get better by retrying it
                logger.log(e.getKind() == DropboxControlException.Kind.UNKNOWN_TYPE ? INFO : WARNING,
                           "Dropping recency ADU " + adu.getADUId() + " from " + clientId + ": " + e.getKind() + " " +
                                   e.getMessage());
            }
        }
        if (newest == null) return;

        String senderId = RecencyRequestSigner.senderIdFor(newest.getTlsPublicKey());
        var queued = queuedGrant(clientId);
        if (queued != null && queued.getRequestSeq() == newest.getRequestSeq() && senderId.equals(senderIdOf(queued))) {
            logger.log(INFO,
                       "Ignoring re-sent recency request " + newest.getRequestSeq() + " from " + clientId +
                               ": its grant is still queued");
            return;
        }

        GetRecencyBlobResponse blob;
        try {
            blob = RecencyBlobMinter.mint(serverSecurity, senderId);
        } catch (InvalidKeyException e) {
            throw new IOException("cannot sign a recency blob for " + clientId, e);
        }
        long issuedAt = blob.getRecencyBlob().getBlobTimestamp();
        byte[] bytes;
        try {
            bytes = DropboxControlCodec.encode(RecencyGrant.of(newest.getRequestSeq(), blob.toByteArray(), issuedAt));
        } catch (DropboxControlException e) {
            // cannot happen: a minted blob is a few hundred bytes, far below MAX_RECENCY_BLOB_CHARS
            logger.log(SEVERE, "Could not encode recency grant " + newest.getRequestSeq() + " for " + clientId, e);
            return;
        }
        enqueueGrant(clientId, bytes);
        upsertRow(clientId, newest, senderId, issuedAt);
        logger.log(INFO,
                   "Queued recency grant " + newest.getRequestSeq() + " for " + clientId + " (senderId " + senderId +
                           ")");
    }

    /**
     * Queues the grant as the only pending recency ADU for the client. Older queued grants are deleted first (the same
     * move as {@code DropboxControlHandler}'s plans), so only the newest blob rides the next bundle; a grant already
     * sent in an unacknowledged bundle is harmless because the dropbox only takes a grant that answers its current
     * request.
     */
    private void enqueueGrant(String clientId, byte[] bytes) throws IOException {
        long lastQueued = sendFolder.getLastADUIdAdded(clientId, DropboxControl.RECENCY_APP_ID);
        if (lastQueued > 0) sendFolder.deleteAllFilesUpTo(clientId, DropboxControl.RECENCY_APP_ID, lastQueued);
        sendFolder.addADU(clientId, DropboxControl.RECENCY_APP_ID, bytes, -1);
    }

    private void upsertRow(String clientId, RecencyRequest request, String senderId, long issuedAt) {
        var row = recencyGrantRepository.findById(clientId).orElse(null);
        if (row == null) {
            row = new DropboxRecencyGrant(clientId, issuedAt);
            logger.log(INFO, "First recency request from " + clientId);
        } else if (row.tlsPublicKey != null && !row.tlsPublicKey.equals(request.getTlsPublicKey())) {
            logger.log(INFO,
                       "Client " + clientId + " changed its TLS key: senderId " + row.senderId + " -> " + senderId);
        }
        row.tlsPublicKey = request.getTlsPublicKey();
        row.senderId = senderId;
        row.lastRequestSeq = request.getRequestSeq();
        row.lastGrantMs = issuedAt;
        recencyGrantRepository.save(row);
    }

    /**
     * Whether a grant ADU is queued for the client. It checks the app folder first, because {@link StoreADUs} creates
     * metadata for any (client, app) it is asked about, and this runs for every client's bundle generation.
     */
    private boolean grantQueued(String clientId) {
        if (!sendFolder.getADUFile(clientId, DropboxControl.RECENCY_APP_ID, 0).getParentFile().isDirectory()) {
            return false;
        }
        return sendFolder.getLastADUIdAdded(clientId, DropboxControl.RECENCY_APP_ID) >
                sendFolder.getLastADUIdDeleted(clientId, DropboxControl.RECENCY_APP_ID);
    }

    /** the grant ADU still queued for the client, or null if none is queued (or it cannot be read) */
    private RecencyGrant queuedGrant(String clientId) {
        if (!grantQueued(clientId)) return null;
        long lastAdded = sendFolder.getLastADUIdAdded(clientId, DropboxControl.RECENCY_APP_ID);
        try {
            return DropboxControlCodec.decodeRecencyGrant(sendFolder.getADU(clientId,
                                                                             DropboxControl.RECENCY_APP_ID,
                                                                             lastAdded));
        } catch (IOException | DropboxControlException | RuntimeException e) {
            logger.log(WARNING, "Cannot read the recency grant queued for " + clientId, e);
            return null;
        }
    }

    /** the senderId a queued grant's blob names, or null if the blob cannot be parsed */
    private static String senderIdOf(RecencyGrant grant) {
        try {
            return GetRecencyBlobResponse.parseFrom(grant.getRecencyBlobBytes()).getRecencyBlob().getSenderId();
        } catch (IOException e) {
            return null;
        }
    }
}
