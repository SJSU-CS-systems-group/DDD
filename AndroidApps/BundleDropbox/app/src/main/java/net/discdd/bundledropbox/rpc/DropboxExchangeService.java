package net.discdd.bundledropbox.rpc;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.discdd.bundledropbox.cycle.Clock;
import net.discdd.bundledropbox.store.BundleRecord;
import net.discdd.bundledropbox.store.DeliveryLog;
import net.discdd.bundledropbox.store.Direction;
import net.discdd.bundledropbox.store.RecencyBlobStore;
import net.discdd.bundledropbox.store.RelayStore;
import net.discdd.bundledropbox.store.Staged;
import net.discdd.bundlerouting.service.BundleExchangeServiceImpl;
import net.discdd.bundlesecurity.BundleOwnershipPSI;
import net.discdd.grpc.BundleChunk;
import net.discdd.grpc.BundleDownloadRequest;
import net.discdd.grpc.BundleDownloadResponse;
import net.discdd.grpc.BundleSenderType;
import net.discdd.grpc.BundleUploadRequest;
import net.discdd.grpc.BundleUploadResponse;
import net.discdd.grpc.GetRecencyBlobRequest;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.PSIDownloadRequest;
import net.discdd.grpc.PSIElement;
import net.discdd.grpc.PSIRequest;
import net.discdd.grpc.PSIResponse;
import net.discdd.grpc.PublicKeyMap;
import net.discdd.utils.BundleUtils;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The dropbox's implementation of the {@code BundleExchangeService} that unmodified BundleClients call
 * in HOST mode. Adapted from BundleTransport's {@code RpcServer} anonymous subclass (Research/R1 §3, §6),
 * but backed by the dropbox {@link RelayStore} instead of the transport's two flat directories.
 *
 * <p>Mapping onto the store:
 * <ul>
 *   <li><b>uploadBundle</b> (client to server traffic): written to a {@link RelayStore#beginStaging staged}
 *       file, committed to {@link Direction#UPSTREAM} under the client's claimed encrypted bundle id only
 *       after the stream completed successfully, otherwise aborted. Refused when the store is full or the
 *       id is unclean. The transport writes straight to the final name, so a partial upload would later
 *       be forwarded truncated (Research/R1 §3.2); staging prevents that.</li>
 *   <li><b>psiExchange / psiDownloadBundle</b> (server to client traffic): exactly the transport's semantics
 *       (at most 10 blinded client values, a per-session snapshot of the DOWNSTREAM file names, 5 min TTL,
 *       session removed after a successful download) over committed DOWNSTREAM bundles only.</li>
 *   <li><b>downloadBundle</b> (legacy, only used if PSI is UNIMPLEMENTED): DOWNSTREAM committed files only.</li>
 *   <li><b>getRecencyBlob</b>: the carrier's server-signed blob from {@link RecencyBlobStore}, served
 *       verbatim.</li>
 * </ul>
 *
 * <p>Stock-client compatibility rules this class must keep (Research/R3 §2, §7):
 * <ul>
 *   <li>DOWNSTREAM file names are the encrypted bundle ids: the client's PSI hashes the file names, so a
 *       renamed file never matches.</li>
 *   <li>{@code psiExchange} must not fail with anything but UNIMPLEMENTED for normal input. The client calls
 *       it outside its inner try, so any other error aborts the exchange and skips the upload too.</li>
 *   <li>{@code getRecencyBlob} must return a SUCCESS blob signed by the client's server, or the client
 *       aborts the whole exchange. The dropbox only hosts once it has one (bootstrap rule).</li>
 * </ul>
 *
 * <p>Threading: called on the gRPC server executor; methods for different calls run concurrently. Calls
 * on one stream are serialized by gRPC. The store, blob store and delivery log are thread-safe; PSI sessions
 * live in a {@link ConcurrentHashMap}. Admission and session accounting happen before this class, in
 * {@link AdmissionInterceptor}.
 */
public final class DropboxExchangeService extends BundleExchangeServiceImpl {
    private static final Logger logger = Logger.getLogger(DropboxExchangeService.class.getName());

    /**
     * Max blinded values per {@code psiExchange}. Same as the transport ({@code RpcServer}); matches the
     * client's request window {@code Constants.DEFAULT_WINDOW_SIZE = 10}, so the stock client never
     * exceeds it.
     */
    public static final int MAX_PSI_ELEMENTS_PER_REQUEST = 10;

    /** PSI sessions older than this are purged / refused, as in the transport. */
    public static final long PSI_SESSION_TTL_MS = 5 * 60 * 1000;

    /**
     * Upload state of the call currently executing on this thread. bundle-core's upload observer calls
     * {@link #pathProducer} and {@link #bundleCompletion} synchronously from inside {@code onNext} /
     * {@code onCompleted}, and those hooks carry no per-call context, so the wrapping observer binds it here
     * for the duration of each delegated callback.
     */
    private static final ThreadLocal<UploadContext> CURRENT_UPLOAD = new ThreadLocal<>();

    private final RelayStore store;
    private final RecencyBlobStore recencyBlobs;
    private final DeliveryLog deliveryLog;
    private final RpcEventListener listener;
    private final Clock clock;
    private final LongSupplier wallClock;
    private final ConcurrentHashMap<String, PsiSession> psiSessions = new ConcurrentHashMap<>();

    /**
     * @param store        relay store (UPSTREAM receives client uploads, DOWNSTREAM is served to clients)
     * @param recencyBlobs cached carrier recency blob
     * @param deliveryLog  records DOWNSTREAM bundles streamed to clients (FUTURE: client-ACK reports)
     * @param listener     status events; may be {@link RpcEventListener#NO_OP}
     * @param clock        monotonic clock for PSI session TTLs
     * @param wallClock    wall clock (epoch millis) for {@link DeliveryLog} timestamps
     */
    public DropboxExchangeService(RelayStore store,
                                  RecencyBlobStore recencyBlobs,
                                  DeliveryLog deliveryLog,
                                  RpcEventListener listener,
                                  Clock clock,
                                  LongSupplier wallClock) {
        if (store == null || recencyBlobs == null || deliveryLog == null || clock == null || wallClock == null) {
            throw new NullPointerException("store/recencyBlobs/deliveryLog/clock/wallClock");
        }
        this.store = store;
        this.recencyBlobs = recencyBlobs;
        this.deliveryLog = deliveryLog;
        this.listener = listener == null ? RpcEventListener.NO_OP : listener;
        this.clock = clock;
        this.wallClock = wallClock;
    }

    /** Drops all PSI sessions (called when the server stops; clients start a fresh PSI next time). */
    public void clearPsiSessions() {
        psiSessions.clear();
    }

    /** @return number of live PSI sessions (for tests and diagnostics). */
    public int psiSessionCount() {
        return psiSessions.size();
    }

    @Override
    protected void onBundleExchangeEvent(BundleExchangeEvent event) {
        listener.onExchangeEvent(event);
    }

    // ---------------------------------------------------------------------------------------------
    // getRecencyBlob
    // ---------------------------------------------------------------------------------------------

    /**
     * Serves the cached carrier blob. It is parsed and re-serialized unchanged; the client re-serializes
     * {@code recencyBlob} itself to verify the signature, so the signed content is identical (Research/R3
     * §3 option c). Never fabricate or re-sign a blob.
     *
     * <p>With no blob the default instance (status UNDEFINED) is returned, exactly what a transport that
     * never reached the server returns. The stock client aborts on it ({@code RecencyException}), which is
     * why the cycle never hosts without a blob; this branch only covers races (e.g. operator cleared the
     * store while hosting).
     */
    @Override
    public void getRecencyBlob(GetRecencyBlobRequest request,
                               StreamObserver<GetRecencyBlobResponse> responseObserver) {
        GetRecencyBlobResponse response = GetRecencyBlobResponse.getDefaultInstance();
        Optional<byte[]> raw = recencyBlobs.current();
        if (raw.isPresent()) {
            try {
                response = GetRecencyBlobResponse.parseFrom(raw.get());
            } catch (InvalidProtocolBufferException e) {
                logger.log(Level.SEVERE, "Stored recency blob does not parse; serving default instance", e);
            }
        } else {
            logger.log(Level.WARNING,
                       "No recency blob yet; client " + RemoteAddrInterceptor.currentIp() +
                               " will abort its exchange (dropbox must visit a transport first)");
        }
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    // ---------------------------------------------------------------------------------------------
    // uploadBundle (client -> dropbox UPSTREAM)
    // ---------------------------------------------------------------------------------------------

    /**
     * Wraps bundle-core's upload observer so the upload is staged and committed only on success.
     *
     * <p>Why the commit happens when the response is sent, not in {@link #bundleCompletion}: bundle-core
     * does not pass the upload status to {@code bundleCompletion}, and on its error path it calls
     * {@code bundleCompletion} before closing the file and then a second time from {@code onCompleted}.
     * The {@code BundleUploadResponse} it sends afterwards carries the final status and is always produced
     * after the file is closed, so that is the single reliable "upload finished" point. Committing before
     * forwarding the response also lets us report FAILED to the client if the commit fails.
     */
    @Override
    public StreamObserver<BundleUploadRequest> uploadBundle(StreamObserver<BundleUploadResponse> responseObserver) {
        final UploadContext ctx = new UploadContext(RemoteAddrInterceptor.currentIp());
        final StreamObserver<BundleUploadResponse> finishing = new FinishingResponseObserver(ctx, responseObserver);
        final StreamObserver<BundleUploadRequest> base = super.uploadBundle(finishing);
        return new StreamObserver<BundleUploadRequest>() {
            @Override
            public void onNext(BundleUploadRequest value) {
                if (ctx.isFinished()) {
                    // bundle-core already answered (an earlier error); it would hit a closed writer and
                    // answer again. Drop the rest of the client's stream quietly.
                    return;
                }
                withUpload(ctx, () -> base.onNext(value));
            }

            @Override
            public void onError(Throwable t) {
                // Client cancelled or the link dropped (e.g. dropbox left HOST mode mid-upload).
                try {
                    if (!ctx.isFinished()) withUpload(ctx, () -> base.onError(t));
                } finally {
                    ctx.abortIfPending("stream error: " + t);
                }
            }

            @Override
            public void onCompleted() {
                try {
                    if (!ctx.isFinished()) withUpload(ctx, base::onCompleted);
                } finally {
                    ctx.abortIfPending("stream ended without a successful response");
                }
            }
        };
    }

    private static void withUpload(UploadContext ctx, Runnable r) {
        UploadContext previous = CURRENT_UPLOAD.get();
        CURRENT_UPLOAD.set(ctx);
        try {
            r.run();
        } finally {
            if (previous == null) CURRENT_UPLOAD.remove();
            else CURRENT_UPLOAD.set(previous);
        }
    }

    /**
     * Upload: refuse unclean ids (the transport does not sanitize upload ids and they become file names,
     * Research/R1 §3.2) and refuse when the store is full; otherwise start a staged file. Returning
     * {@code null} makes bundle-core fail the stream and answer {@code Status.FAILED}.
     * Note that {@code senderType} is always {@code null} here: the stock client sends it last.
     *
     * <p>Download (legacy {@code downloadBundle}): committed DOWNSTREAM files only, {@code null} if absent.
     */
    @Override
    protected Path pathProducer(BundleExchangeName bundleExchangeName,
                                BundleSenderType senderType,
                                PublicKeyMap publicKeyMap) {
        String id = bundleExchangeName.encryptedBundleId();
        if (bundleExchangeName.isDownload()) {
            return store.pathFor(Direction.DOWNSTREAM, id);
        }
        UploadContext ctx = CURRENT_UPLOAD.get();
        if (ctx == null) {
            logger.log(Level.SEVERE, "pathProducer called for an upload outside an upload context");
            return null;
        }
        ctx.claimedId = id;
        try {
            BundleUtils.checkIdClean(id);
        } catch (RuntimeException e) {
            ctx.failReason = "unclean bundle id";
            return null;
        }
        if (!store.hasCapacityFor(0)) {
            // FUTURE: server keep/delete control ADUs (RetentionPolicy) could free DOWNSTREAM space here
            // instead of refusing; v1 never deletes on its own because it cannot know what was delivered.
            ctx.failReason = "store full";
            return null;
        }
        try {
            Staged staged = store.beginStaging(Direction.UPSTREAM);
            ctx.replaceStaged(staged);
            return staged.path();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Cannot stage upload " + id, e);
            ctx.failReason = "staging failed: " + e.getMessage();
            return null;
        }
    }

    /**
     * Called by bundle-core once the client has sent its sender type, which the stock client sends last
     * (Research/R1 §3.2). We only note that the stream reached its end; the commit itself happens when the
     * final response is sent (see {@link #uploadBundle}). Downloads never call this.
     */
    @Override
    protected void bundleCompletion(BundleExchangeName bundleExchangeName, BundleSenderType senderType, Path path) {
        UploadContext ctx = CURRENT_UPLOAD.get();
        if (ctx != null && !bundleExchangeName.isDownload()) {
            ctx.markCompletionSeen(path);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // downloadBundle (legacy, dropbox DOWNSTREAM -> client)
    // ---------------------------------------------------------------------------------------------

    /**
     * Legacy download. The stock client only uses it if {@code psiExchange} is UNIMPLEMENTED. Validates the
     * id first: bundle-core's {@code checkIdClean} throws outside its try (an unbalanced event and an
     * UNKNOWN status, Research/R1 §3.5), so we answer INVALID_ARGUMENT instead. Records the bundle as served
     * when the stream completes.
     */
    @Override
    public void downloadBundle(BundleDownloadRequest request, StreamObserver<BundleDownloadResponse> responseObserver) {
        final String id = request.getBundleId().getEncryptedId();
        try {
            BundleUtils.checkIdClean(id);
        } catch (RuntimeException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Invalid bundle id").asException());
            return;
        }
        super.downloadBundle(request, new StreamObserver<BundleDownloadResponse>() {
            @Override
            public void onNext(BundleDownloadResponse value) {
                responseObserver.onNext(value);
            }

            @Override
            public void onError(Throwable t) {
                responseObserver.onError(t);
            }

            @Override
            public void onCompleted() {
                responseObserver.onCompleted();
                recordServed(id);
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // PSI (dropbox DOWNSTREAM -> client), copied from the transport's RpcServer semantics
    // ---------------------------------------------------------------------------------------------

    /**
     * Private set intersection step 1, identical to the transport: the client sends up to 10 blinded hashes
     * of the encrypted bundle ids it wants; we reply with its values doubly blinded plus our own blinded
     * DOWNSTREAM ids, and remember the id order under a fresh session id so {@link #psiDownloadBundle} can
     * resolve an index. Neither side learns the other's non-matching ids.
     *
     * <p>Compatibility: the client treats any error other than UNIMPLEMENTED as fatal for the whole exchange
     * and then skips its upload (Research/R3 §7). So an unexpected internal failure is reported as
     * UNIMPLEMENTED, which makes the client fall back to the legacy {@code downloadBundle} probe and still
     * upload. The over-limit case keeps the transport's INVALID_ARGUMENT; the stock client never sends more
     * than its window of 10.
     */
    @Override
    public void psiExchange(PSIRequest request, StreamObserver<PSIResponse> responseObserver) {
        onBundleExchangeEvent(BundleExchangeEvent.DOWNLOAD_STARTED);
        try {
            int numElements = request.getClientBlindedValuesCount();
            if (numElements > MAX_PSI_ELEMENTS_PER_REQUEST) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(
                        "Too many PSI elements: " + numElements + ", maximum allowed: " +
                                MAX_PSI_ELEMENTS_PER_REQUEST).asException());
                return;
            }
            var psi = new BundleOwnershipPSI();
            BigInteger secret = psi.generateSecret();

            // Snapshot of committed DOWNSTREAM ids (file names == encrypted bundle ids). Staged downloads
            // and the recency blob live elsewhere, so nothing needs filtering here.
            List<String> bundleIds = downstreamIds();

            List<BigInteger> clientBlinded = new ArrayList<>(numElements);
            for (PSIElement e : request.getClientBlindedValuesList()) {
                clientBlinded.add(new BigInteger(1, e.getValue().toByteArray()));
            }
            var transportResponse = psi.transportProcess(clientBlinded, bundleIds, secret);

            String sessionId = UUID.randomUUID().toString();
            long now = clock.nowMillis();
            psiSessions.put(sessionId, new PsiSession(bundleIds, now));
            long cutoff = now - PSI_SESSION_TTL_MS;
            psiSessions.entrySet().removeIf(e -> e.getValue().createdAtMs < cutoff);

            var builder = PSIResponse.newBuilder().setSessionId(sessionId);
            for (BigInteger v : transportResponse.doublyBlindedClientValues()) {
                builder.addDoublyBlindedClientValues(PSIElement.newBuilder()
                                                             .setValue(ByteString.copyFrom(v.toByteArray()))
                                                             .build());
            }
            for (BigInteger v : transportResponse.transportBlindedValues()) {
                builder.addTransportBlindedValues(PSIElement.newBuilder()
                                                          .setValue(ByteString.copyFrom(v.toByteArray()))
                                                          .build());
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "PSI exchange failed; telling client UNIMPLEMENTED so it still uploads", e);
            responseObserver.onError(Status.UNIMPLEMENTED.withDescription("PSI unavailable: " + e.getMessage())
                                             .asException());
        } finally {
            onBundleExchangeEvent(BundleExchangeEvent.DOWNLOAD_FINISHED);
        }
        // FUTURE: PSI known-ID skip / destination manifests could narrow the DOWNSTREAM set per client.
    }

    /**
     * PSI step 2, identical to the transport: stream the DOWNSTREAM bundle at {@code transportIndex} of the
     * session snapshot. A session is single-use: it is removed after a successful download (the stock
     * client downloads at most one bundle per exchange, Research/R3 §7). Errors here are caught per match
     * by the client and do not abort its upload.
     */
    @Override
    public void psiDownloadBundle(PSIDownloadRequest request, StreamObserver<BundleDownloadResponse> responseObserver) {
        onBundleExchangeEvent(BundleExchangeEvent.DOWNLOAD_STARTED);
        try {
            PsiSession session = psiSessions.get(request.getSessionId());
            if (session == null || clock.nowMillis() - session.createdAtMs > PSI_SESSION_TTL_MS) {
                if (session != null) psiSessions.remove(request.getSessionId());
                responseObserver.onError(Status.NOT_FOUND.withDescription("PSI session expired or not found")
                                                 .asException());
                return;
            }
            int index = request.getTransportIndex();
            if (index < 0 || index >= session.bundleIds.size()) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Invalid transport index")
                                                 .asException());
                return;
            }
            String bundleId = session.bundleIds.get(index);
            // Re-resolve through the store: the bundle may have been cleared since the snapshot.
            Path path = store.pathFor(Direction.DOWNSTREAM, bundleId);
            if (path == null || !Files.exists(path)) {
                responseObserver.onError(Status.NOT_FOUND.withDescription("Bundle file not found").asException());
                return;
            }
            try (InputStream is = Files.newInputStream(path, StandardOpenOption.READ)) {
                transferToStream(is,
                                 bytes -> responseObserver.onNext(BundleDownloadResponse.newBuilder()
                                                                          .setChunk(BundleChunk.newBuilder()
                                                                                            .setChunk(bytes)
                                                                                            .build())
                                                                          .build()));
            }
            responseObserver.onCompleted();
            psiSessions.remove(request.getSessionId());
            recordServed(bundleId);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "PSI download failed", e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asException());
        } finally {
            onBundleExchangeEvent(BundleExchangeEvent.DOWNLOAD_FINISHED);
        }
    }

    private List<String> downstreamIds() {
        List<BundleRecord> records = store.list(Direction.DOWNSTREAM);
        List<String> ids = new ArrayList<>(records.size());
        for (BundleRecord r : records) ids.add(r.bundleId());
        return Collections.unmodifiableList(ids);
    }

    private void recordServed(String bundleId) {
        try {
            // FUTURE: client-ACK reports. "Served" is not proof of delivery; the client's ack for this
            // bundle travels inside its NEXT upstream bundle. Once the dropbox has a control endpoint it
            // will report these so the server can issue keep/delete decisions (RetentionPolicy).
            deliveryLog.recordServed(bundleId, wallClock.getAsLong());
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not record served bundle " + bundleId, e);
        }
        listener.onDownloadServed(bundleId);
    }

    // ---------------------------------------------------------------------------------------------
    // Upload plumbing
    // ---------------------------------------------------------------------------------------------

    /** Snapshot of DOWNSTREAM ids for one PSI exchange (the transport's {@code PSISession}). */
    private static final class PsiSession {
        final List<String> bundleIds;
        final long createdAtMs;

        PsiSession(List<String> bundleIds, long createdAtMs) {
            this.bundleIds = bundleIds;
            this.createdAtMs = createdAtMs;
        }
    }

    /**
     * Per-call upload state. Mutated from the call's serialized gRPC callbacks; {@code synchronized} guards
     * the commit/abort decision against the cancellation path.
     */
    private final class UploadContext {
        final String remoteIp;
        String claimedId;     // set by pathProducer
        String failReason;    // set when pathProducer refuses
        private Staged staged; // null once committed or aborted
        private boolean completionSeen;
        private boolean finished; // a response has been produced

        UploadContext(String remoteIp) {
            this.remoteIp = remoteIp;
        }

        synchronized boolean isFinished() {
            return finished;
        }

        synchronized void replaceStaged(Staged s) {
            // A second bundleId message in one stream restarts the upload; drop the earlier file.
            if (staged != null) store.abort(staged);
            staged = s;
            completionSeen = false;
        }

        synchronized void markCompletionSeen(Path path) {
            if (staged != null && staged.path().equals(path)) completionSeen = true;
        }

        /**
         * Decides the upload's fate when bundle-core produces its response.
         *
         * @return the response to forward, or {@code null} if a response was already forwarded
         */
        BundleUploadResponse finish(BundleUploadResponse response) {
            Staged toCommit;
            String failure = null;
            synchronized (this) {
                if (finished) return null;
                finished = true;
                toCommit = staged;
                staged = null;
                if (toCommit == null) {
                    failure = failReason != null ? failReason : "no bundle received";
                } else if (response.getStatus() != net.discdd.grpc.Status.SUCCESS) {
                    failure = "stream failed";
                } else if (!completionSeen) {
                    failure = "stream ended before sender type";
                } else if (!store.hasCapacityFor(0)) {
                    // The size is only known once received; enforce the cap before making it visible.
                    failure = "store full";
                }
            }
            if (failure == null) {
                try {
                    BundleRecord record = store.commit(toCommit, claimedId);
                    logger.log(Level.INFO, "Committed upload " + record.bundleId() + " from " + remoteIp);
                    listener.onUploadCommitted(record);
                    return response;
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Commit of upload " + claimedId + " failed", e);
                    failure = "commit failed: " + e.getMessage();
                }
            }
            if (toCommit != null) store.abort(toCommit);
            logger.log(Level.INFO, "Upload " + claimedId + " from " + remoteIp + " discarded: " + failure);
            listener.onUploadFailed(claimedId, failure);
            return response.getStatus() == net.discdd.grpc.Status.SUCCESS ?
                   BundleUploadResponse.newBuilder().setStatus(net.discdd.grpc.Status.FAILED).build() :
                   response;
        }

        /** Safety net for paths where no response was produced (cancelled call, thrown callback). */
        void abortIfPending(String why) {
            Staged toAbort;
            synchronized (this) {
                if (finished) return;
                finished = true;
                toAbort = staged;
                staged = null;
            }
            if (toAbort != null) {
                store.abort(toAbort);
                listener.onUploadFailed(claimedId, why);
            }
        }
    }

    /**
     * Intercepts bundle-core's single {@code BundleUploadResponse} to commit/abort first, forwards it at most
     * once, and swallows the exceptions gRPC throws when answering an already-cancelled call (bundle-core
     * answers from its error path too, Research/R1 §3.2), so its UPLOAD_FINISHED event still fires.
     */
    private static final class FinishingResponseObserver implements StreamObserver<BundleUploadResponse> {
        private final UploadContext ctx;
        private final StreamObserver<BundleUploadResponse> delegate;
        private boolean forwarded;
        private boolean closed;

        FinishingResponseObserver(UploadContext ctx, StreamObserver<BundleUploadResponse> delegate) {
            this.ctx = ctx;
            this.delegate = delegate;
        }

        @Override
        public void onNext(BundleUploadResponse value) {
            BundleUploadResponse out = ctx.finish(value);
            if (out == null || forwarded) return;
            forwarded = true;
            try {
                delegate.onNext(out);
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "Upload response not delivered (call closed)", e);
            }
        }

        @Override
        public void onError(Throwable t) {
            ctx.abortIfPending("stream error: " + t);
            if (closed) return;
            closed = true;
            try {
                delegate.onError(t);
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "Upload error not delivered (call closed)", e);
            }
        }

        @Override
        public void onCompleted() {
            ctx.abortIfPending("completed without response");
            if (closed) return;
            closed = true;
            try {
                delegate.onCompleted();
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "Upload completion not delivered (call closed)", e);
            }
        }
    }
}
