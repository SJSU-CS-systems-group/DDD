package net.discdd.bundledropbox.visit;

import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.StreamObserver;
import net.discdd.bundledropbox.store.BundleRecord;
import net.discdd.bundledropbox.store.Direction;
import net.discdd.bundledropbox.store.ForwardLedger;
import net.discdd.bundledropbox.store.RecencyBlobStore;
import net.discdd.bundledropbox.store.RelayStore;
import net.discdd.bundledropbox.store.Staged;
import net.discdd.grpc.BundleChunk;
import net.discdd.grpc.BundleDownloadResponse;
import net.discdd.grpc.BundleExchangeServiceGrpc;
import net.discdd.grpc.BundleSenderType;
import net.discdd.grpc.BundleUploadRequest;
import net.discdd.grpc.BundleUploadResponse;
import net.discdd.grpc.EncryptedBundleId;
import net.discdd.grpc.GetRecencyBlobRequest;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.PSIDownloadRequest;
import net.discdd.grpc.PSIRequest;
import net.discdd.grpc.PSIResponse;
import net.discdd.grpc.RecencyBlobStatus;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.tls.GrpcSecurityKey;
import net.discdd.utils.BundleUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

/**
 * The dropbox's VISIT-mode worker: one gRPC exchange with a carrier (an unmodified BundleTransport) that the
 * Wi-Fi layer has already joined. To the carrier the dropbox looks exactly like a BundleClient.
 *
 * <p>One {@link #visit} performs 03 Architecture step 3 / Research/R2 §8, in this order:
 * <ol>
 *   <li><b>Socket probe</b> of {@code host:7777} with bounded retries, as the stock client does
 *       ({@code ClientBundleTransmission.isServerRunning}): right after a P2P join the carrier's port may not be
 *       routable yet.</li>
 *   <li><b>{@code getRecencyBlob}</b>, offered verbatim to the {@link RecencyBlobStore}. This always runs, even if
 *       {@code keepGoing} is already false: it is cheap and it is the only way a fresh dropbox can ever host
 *       (the stock client aborts the whole exchange without a SUCCESS, server-signed recency blob, R3/R4 §4.4).</li>
 *   <li><b>Upload</b> every UPSTREAM bundle not yet in the {@link ForwardLedger} for this carrier, oldest first.
 *       Upload goes before download so the most valuable direction (client mail toward the internet) gets the
 *       radio time if the transport dwell is short (R2 §8).</li>
 *   <li><b>Download-all</b> of the carrier's client-bound bundles via repeated PSI sessions (R2 §4.3), committing
 *       each new one to DOWNSTREAM under the id read from the jar's {@code bundle.id}.</li>
 * </ol>
 *
 * <p><b>Stopping.</b> {@code keepGoing} is checked BETWEEN bundles only, so a bundle in flight always completes
 * ("flexible" timer rule in 02 Decisions): the role cycle closes its gate at dwell expiry and the visit stops at
 * the next bundle boundary. {@link #cancel()} is the hard stop for the drain cap: it shuts the channel down, the
 * in-flight RPC fails, its staged file is aborted and nothing is marked forwarded, so the work is retried on the
 * next visit.
 *
 * <p><b>Threading.</b> {@link #visit} blocks for the whole exchange; call it on a background worker, never the
 * main thread. Visits on one instance are serialized. {@link #cancel()} may be called from any thread and only
 * affects the visit in progress (a cancel issued while no visit runs is forgotten when the next one starts).
 *
 * <p>Pure Java (no Android APIs) so it is exercised by JVM tests against a localhost fake carrier.
 */
public final class TransportVisitClient {
    private static final Logger logger = Logger.getLogger(TransportVisitClient.class.getName());

    /** gRPC port of the stock transport ({@code RpcServer.port}) and of the dropbox's own host mode. */
    public static final int TRANSPORT_PORT = 7777;

    /**
     * Upload chunk size: identical to the stock client ({@code 1000 * 1000 * 4}). The transport accepts at most
     * 5 MiB per inbound message ({@code RpcServer maxInboundMessageSize}), so a chunk must stay well below it.
     */
    static final int UPLOAD_CHUNK_BYTES = 4_000_000;

    /** Jar entry holding the encrypted bundle id ({@code "bundle.id"}). */
    static final String BUNDLE_ID_ENTRY = SecurityUtils.BUNDLEID_FILENAME;

    /** Upper bound on the size of a {@code bundle.id} entry we are willing to read (ids are at most 100 chars). */
    private static final int MAX_BUNDLE_ID_ENTRY_BYTES = 1024;

    /** Extra wait beyond the RPC deadline for the upload response before giving up locally. */
    private static final long UPLOAD_RESPONSE_GRACE_MS = 5_000;

    private final RelayStore store;
    private final ForwardLedger ledger;
    private final RecencyBlobStore recencyStore;
    private final CarrierChannelFactory channelFactory;
    private final VisitOptions options;
    private final LongSupplier wallClock;

    private final Object visitLock = new Object();
    private volatile boolean cancelRequested;
    private volatile ManagedChannel activeChannel;

    /**
     * Production constructor: TLS channels built like the stock client, stock-client timeouts, system wall clock.
     *
     * @param store          the relay store (UPSTREAM is read, DOWNSTREAM is written)
     * @param ledger         per-carrier record of acknowledged uploads
     * @param recencyStore   cache of the newest server-signed recency blob
     * @param identityOrNull the dropbox TLS identity presented as client certificate, or {@code null}
     */
    public TransportVisitClient(RelayStore store,
                                ForwardLedger ledger,
                                RecencyBlobStore recencyStore,
                                GrpcSecurityKey identityOrNull) {
        this(store,
             ledger,
             recencyStore,
             CarrierChannelFactory.tls(identityOrNull),
             VisitOptions.DEFAULTS,
             System::currentTimeMillis);
    }

    /**
     * Full constructor (tests and custom wiring).
     *
     * @param store          the relay store
     * @param ledger         per-carrier record of acknowledged uploads
     * @param recencyStore   cache of the newest server-signed recency blob
     * @param channelFactory creates one new channel per visit
     * @param options        probe, deadline and pass bounds
     * @param wallClock      wall-clock millis for {@link ForwardLedger#recordForwarded}
     */
    public TransportVisitClient(RelayStore store,
                                ForwardLedger ledger,
                                RecencyBlobStore recencyStore,
                                CarrierChannelFactory channelFactory,
                                VisitOptions options,
                                LongSupplier wallClock) {
        this.store = Objects.requireNonNull(store, "store");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.recencyStore = Objects.requireNonNull(recencyStore, "recencyStore");
        this.channelFactory = Objects.requireNonNull(channelFactory, "channelFactory");
        this.options = Objects.requireNonNull(options, "options");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
    }

    /**
     * Performs one complete visit. Never throws for network or carrier problems: everything is reported in the
     * returned {@link VisitResult}.
     *
     * @param host      the carrier address (Wi-Fi Direct {@code groupOwnerAddress})
     * @param port      the carrier gRPC port, normally {@link #TRANSPORT_PORT}
     * @param carrierId the carrier's DNS-SD {@code transportId}; the {@link ForwardLedger} key
     * @param keepGoing polled between bundles; returning false ends the visit after the in-flight bundle
     *                  (wire it to {@code !gate.isClosing()})
     * @return what happened
     */
    public VisitResult visit(InetAddress host, int port, String carrierId, BooleanSupplier keepGoing) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(carrierId, "carrierId");
        Objects.requireNonNull(keepGoing, "keepGoing");
        synchronized (visitLock) {
            cancelRequested = false;
            return new Visit(host, port, carrierId, keepGoing).run();
        }
    }

    /**
     * Hard-stops the visit in progress (drain cap reached): shuts the channel down so the in-flight RPC fails
     * immediately. The partial bundle is discarded and stays retryable. Safe from any thread; idempotent.
     */
    public void cancel() {
        cancelRequested = true;
        ManagedChannel channel = activeChannel;
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    /**
     * Reads the encrypted bundle id from a bundle jar's {@code bundle.id} entry, trimmed like
     * {@code BundleUtils.extractBundle} does, and validated with {@code BundleUtils.checkIdClean}.
     *
     * <p>The PSI download stream carries only chunks, no name; but every bundle the server makes stores its
     * encrypted id in {@code bundle.id}, and that id is exactly the transport's file name and the string a client
     * blinds in PSI (R2 §4.2). Storing under any other name would make the bundle undeliverable.
     *
     * @param jar a downloaded file
     * @return the clean id, or {@code null} if the file is not a jar, lacks the entry, or the id is unclean
     */
    static String readJarBundleId(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(BUNDLE_ID_ENTRY);
            if (entry == null) return null;
            byte[] raw;
            try (InputStream in = zip.getInputStream(entry)) {
                raw = in.readNBytes(MAX_BUNDLE_ID_ENTRY_BYTES + 1);
            }
            if (raw.length > MAX_BUNDLE_ID_ENTRY_BYTES) return null;
            String id = new String(raw, StandardCharsets.UTF_8).trim();
            BundleUtils.checkIdClean(id);
            return id;
        } catch (IOException | RuntimeException e) {
            logger.log(FINE, "no usable bundle.id in " + jar + ": " + e);
            return null;
        }
    }

    /** Thrown internally when the visit must stop early (keepGoing false, cancel, interrupt). */
    private static final class VisitStopped extends RuntimeException {
        private static final long serialVersionUID = 1L;

        VisitStopped(String reason) { super(reason, null, false, false); }
    }

    /** Thrown internally when the carrier connection is gone (UNAVAILABLE/CANCELLED); ends the visit. */
    private static final class CarrierLost extends RuntimeException {
        private static final long serialVersionUID = 1L;

        CarrierLost(String reason) { super(reason, null, false, false); }
    }

    /** Per-visit mutable state and the step implementations. Used by exactly one thread. */
    private final class Visit {
        private final InetAddress host;
        private final int port;
        private final String carrierId;
        private final BooleanSupplier keepGoing;

        private boolean reachable;
        private boolean recencyFetched;
        private boolean recencyUpdated;
        private int uploaded;
        private int downloaded;
        private int skipped;
        private int errors;
        private boolean stoppedEarly;
        private String lastError;

        Visit(InetAddress host, int port, String carrierId, BooleanSupplier keepGoing) {
            this.host = host;
            this.port = port;
            this.carrierId = carrierId;
            this.keepGoing = keepGoing;
        }

        VisitResult run() {
            logger.log(INFO, "visit to carrier " + carrierId + " at " + host.getHostAddress() + ":" + port);
            try {
                if (!probe()) {
                    error("carrier " + host.getHostAddress() + ":" + port + " not reachable after " +
                                  options.probeAttempts() + " attempts");
                } else {
                    reachable = true;
                    exchange();
                }
            } catch (VisitStopped e) {
                stoppedEarly = true;
                logger.log(INFO, "visit to " + carrierId + " stopped early: " + e.getMessage());
            } catch (CarrierLost e) {
                error(e.getMessage());
            } catch (Exception e) {
                error("visit failed: " + e);
            }
            VisitResult result = new VisitResult(carrierId,
                                                 reachable,
                                                 recencyFetched,
                                                 recencyUpdated,
                                                 uploaded,
                                                 downloaded,
                                                 skipped,
                                                 errors,
                                                 stoppedEarly,
                                                 lastError);
            logger.log(INFO, "visit finished: " + result);
            return result;
        }

        private void exchange() throws Exception {
            ManagedChannel channel = channelFactory.create(host, port);
            // Publish before checking the flag; cancel() sets the flag before reading the channel, so one of the
            // two always sees the other (both fields are volatile).
            activeChannel = channel;
            try {
                if (cancelRequested) throw new VisitStopped("cancelled");
                var blocking = BundleExchangeServiceGrpc.newBlockingStub(channel);
                var async = BundleExchangeServiceGrpc.newStub(channel);
                fetchRecencyBlob(blocking);
                uploadAll(async);
                downloadAll(blocking);
            } finally {
                activeChannel = null;
                channel.shutdownNow();
            }
        }

        // ---------------------------------------------------------------- probe

        /** Plain TCP probe, as the stock client does before its first RPC. */
        private boolean probe() {
            for (int attempt = 1; attempt <= options.probeAttempts(); attempt++) {
                checkCancelled();
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(host, port), options.probeConnectTimeoutMs());
                    return true;
                } catch (IOException e) {
                    logger.log(FINE, "probe " + attempt + " to " + host.getHostAddress() + ":" + port + " failed: " + e);
                }
                if (attempt < options.probeAttempts()) {
                    try {
                        Thread.sleep(options.probeDelayMs());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new VisitStopped("interrupted during probe");
                    }
                }
            }
            return false;
        }

        // ---------------------------------------------------------------- recency

        /**
         * Fetches the carrier's recency blob and offers the serialized response to the store, which keeps it only
         * if it is SUCCESS and strictly newer. A carrier that never synced with the server answers with the
         * default instance (status UNDEFINED), which is normal and not an error.
         *
         * <p>Re-serialization is safe: the server signature covers the {@code RecencyBlob} message, which the
         * stock client re-serializes itself before verifying.
         */
        private void fetchRecencyBlob(BundleExchangeServiceGrpc.BundleExchangeServiceBlockingStub blocking) {
            try {
                GetRecencyBlobResponse response = blocking.withDeadlineAfter(options.shortDeadlineMs(), MILLISECONDS)
                        .getRecencyBlob(GetRecencyBlobRequest.getDefaultInstance());
                if (response.getStatus() != RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS) {
                    logger.log(INFO, "carrier " + carrierId + " has no server-signed recency blob (status " +
                            response.getStatus() + "); it has probably never synced with the server");
                }
                recencyFetched = response.getStatus() == RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS &&
                        response.hasRecencyBlob();
                recencyUpdated = recencyStore.offer(response.toByteArray());
                logger.log(INFO, "recency blob ts=" + response.getRecencyBlob().getBlobTimestamp() + " stored=" +
                        recencyUpdated);
            } catch (StatusRuntimeException e) {
                rpcFailed(e.getStatus(), "getRecencyBlob");
            }
        }

        // ---------------------------------------------------------------- upload

        /**
         * Uploads every committed UPSTREAM bundle this carrier has not acknowledged yet. The ledger is updated only
         * after a SUCCESS response, so failures are retried on the next visit. The carrier stores each upload
         * verbatim as {@code BundleTransmission/server/<id>} and forwards it on its next server sync; it does no
         * identity or sender-type checks (R2 §3), so relaying other clients' bundles works unmodified.
         *
         * <p>FUTURE: dropbox control endpoint — also upload the dropbox's own control bundle (client-ACK reports
         * from {@code DeliveryLog}, holdings) signed with its own keys, so the server can classify it as a dropbox
         * and answer with keep/delete control ADUs (R4 §5 items 1 and 3).
         */
        private void uploadAll(BundleExchangeServiceGrpc.BundleExchangeServiceStub async) {
            for (BundleRecord record : store.list(Direction.UPSTREAM)) {
                String bundleId = record.bundleId();
                if (ledger.wasForwardedTo(bundleId, carrierId)) continue;
                checkContinue();
                Path path = store.pathFor(Direction.UPSTREAM, bundleId);
                if (path == null) continue; // removed by the operator since list()
                try {
                    // The transport never sanitizes upload ids and uses them as file names (R2 §3); the store
                    // already validated on commit, re-check before sending anything.
                    BundleUtils.checkIdClean(bundleId);
                } catch (RuntimeException e) {
                    error("refusing to upload unclean id " + bundleId);
                    continue;
                }
                if (uploadOne(async, bundleId, path)) {
                    ledger.recordForwarded(bundleId, carrierId, wallClock.getAsLong());
                    uploaded++;
                    logger.log(INFO, "uploaded " + bundleId + " to " + carrierId);
                }
            }
        }

        /**
         * One {@code uploadBundle} stream in the stock client's message order: {@code bundleId}, chunks of at most
         * 4,000,000 bytes, {@code senderType=CLIENT}, then half-close and wait for the response.
         *
         * @return true only if the carrier answered {@code SUCCESS}
         */
        private boolean uploadOne(BundleExchangeServiceGrpc.BundleExchangeServiceStub async, String bundleId, Path path) {
            CompletableFuture<BundleUploadResponse> response = new CompletableFuture<>();
            StreamObserver<BundleUploadResponse> responseObserver = new StreamObserver<>() {
                @Override
                public void onNext(BundleUploadResponse value) { response.complete(value); }

                @Override
                public void onError(Throwable t) { response.completeExceptionally(t); }

                @Override
                public void onCompleted() { response.complete(null); }
            };
            var requests = (ClientCallStreamObserver<BundleUploadRequest>) async.withDeadlineAfter(options.longDeadlineMs(),
                                                                                                     MILLISECONDS)
                    .uploadBundle(responseObserver);
            boolean sentAll = false;
            try (InputStream in = Files.newInputStream(path)) {
                requests.onNext(BundleUploadRequest.newBuilder()
                                        .setBundleId(EncryptedBundleId.newBuilder().setEncryptedId(bundleId))
                                        .build());
                byte[] buffer = new byte[UPLOAD_CHUNK_BYTES];
                int n;
                while ((n = in.readNBytes(buffer, 0, buffer.length)) > 0) {
                    if (!awaitReady(requests, response)) break;
                    requests.onNext(BundleUploadRequest.newBuilder()
                                            .setChunk(BundleChunk.newBuilder().setChunk(ByteString.copyFrom(buffer, 0, n)))
                                            .build());
                }
                if (!response.isDone()) {
                    // senderType last, exactly like the stock client; the transport ignores it anyway.
                    requests.onNext(BundleUploadRequest.newBuilder().setSenderType(BundleSenderType.CLIENT).build());
                    requests.onCompleted();
                    sentAll = true;
                }
            } catch (IOException e) {
                requests.cancel("local read failed", e);
                error("cannot read upstream bundle " + bundleId + ": " + e.getMessage());
                return false;
            } catch (RuntimeException e) {
                requests.cancel("visit stopped", e);
                throw e;
            }

            BundleUploadResponse reply;
            try {
                reply = response.get(options.longDeadlineMs() + UPLOAD_RESPONSE_GRACE_MS, MILLISECONDS);
            } catch (ExecutionException e) {
                rpcFailed(io.grpc.Status.fromThrowable(e.getCause()), "upload of " + bundleId);
                return false;
            } catch (TimeoutException e) {
                requests.cancel("no upload response", null);
                error("no upload response for " + bundleId);
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                requests.cancel("interrupted", e);
                throw new VisitStopped("interrupted during upload");
            }
            if (!sentAll || reply == null || reply.getStatus() != net.discdd.grpc.Status.SUCCESS) {
                error("carrier did not acknowledge " + bundleId + " (" + (reply == null ? "no reply" : reply.getStatus()) +
                              ")");
                return false;
            }
            return true;
        }

        /**
         * Light outbound flow control: the stock client pushes chunks without waiting, which buffers the whole
         * bundle in memory. Waiting for {@code isReady()} bounds that to roughly one chunk. Only cancel stops this
         * wait; {@code keepGoing} does not, because the in-flight bundle must complete.
         *
         * @return false if the call already finished (error) and nothing more should be sent
         */
        private boolean awaitReady(ClientCallStreamObserver<BundleUploadRequest> requests,
                                   CompletableFuture<BundleUploadResponse> response) {
            long giveUpAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.longDeadlineMs());
            while (!requests.isReady()) {
                if (response.isDone()) return false;
                checkCancelled();
                if (System.nanoTime() > giveUpAt) return true; // let the RPC deadline report the failure
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new VisitStopped("interrupted during upload");
                }
            }
            return !response.isDone();
        }

        // ---------------------------------------------------------------- download-all

        /**
         * v1 "simple, wasteful" download-all (02 Decisions; R2 §4.3). The transport has no listing RPC, but:
         * <ul>
         *   <li>{@code psiExchange} with ZERO client elements is allowed and returns exactly one transport-blinded
         *       value per client-bound file, so its count is N;</li>
         *   <li>{@code psiDownloadBundle(session, i)} serves ANY index {@code 0..N-1} without checking for a PSI
         *       match;</li>
         *   <li>but a session is removed after one successful download, so every bundle after the first needs a
         *       fresh {@code psiExchange} (the counting session is reused for index 0). That costs the carrier
         *       about N^2 modPows per visit, acceptable for tens of bundles.</li>
         * </ul>
         * Each new session re-lists the carrier's directory in {@code File.listFiles()} order, which is stable
         * only while the directory is unchanged. So the bundle id is taken from the jar, duplicates are dropped,
         * and one extra pass runs (bounded by {@link VisitOptions#maxDownloadPasses()}) if the count changed or a
         * duplicate showed up during a pass, both signs that an index may have been missed.
         *
         * <p>FUTURE: PSI known-ID skip — send up to 10 blinded ids the dropbox already holds per
         * {@code psiExchange} and download only unmatched indices (R2 §4.3 optional optimization).
         * <p>FUTURE: server-issued destination manifests replace download-all, so the dropbox pulls only bundles
         * for clients that actually use it (02 Decisions, R4 §5 item 2).
         */
        private void downloadAll(BundleExchangeServiceGrpc.BundleExchangeServiceBlockingStub blocking) {
            for (int pass = 1; pass <= options.maxDownloadPasses(); pass++) {
                checkContinue();
                PSIResponse session = countingExchange(blocking);
                if (session == null) return;
                int n = session.getTransportBlindedValuesCount();
                logger.log(INFO, "download pass " + pass + ": carrier " + carrierId + " holds " + n + " client bundles");
                boolean listingChanged = false;
                int duplicatesInPass = 0;
                Set<String> idsThisPass = new HashSet<>();
                for (int i = 0; i < n; i++) {
                    checkContinue();
                    if (!store.hasCapacityFor(0)) {
                        error("store full; stopping downloads");
                        return;
                    }
                    if (session == null) {
                        session = countingExchange(blocking);
                        if (session == null) return;
                        int m = session.getTransportBlindedValuesCount();
                        if (m != n) {
                            logger.log(INFO, "carrier listing changed during pass: " + n + " -> " + m);
                            listingChanged = true;
                            if (i >= m) break;
                        }
                    }
                    IndexOutcome outcome = downloadIndex(blocking, session.getSessionId(), i);
                    // A successful download consumes the session on the carrier; after an error the session state
                    // is ambiguous (expired vs file gone), so always start the next index with a fresh one.
                    session = null;
                    if (outcome.listingChanged) listingChanged = true;
                    if (outcome.bundleId != null && !idsThisPass.add(outcome.bundleId)) duplicatesInPass++;
                }
                if (!listingChanged && duplicatesInPass == 0) return;
                logger.log(INFO, "pass " + pass + " may have missed bundles (listingChanged=" + listingChanged +
                        ", duplicates=" + duplicatesInPass + ")");
            }
        }

        /**
         * {@code psiExchange} with an empty request: never reveals anything about the dropbox's holdings and costs
         * the dropbox no modPows.
         *
         * @return the response, or {@code null} if downloads must stop (non-fatal error already recorded)
         */
        private PSIResponse countingExchange(BundleExchangeServiceGrpc.BundleExchangeServiceBlockingStub blocking) {
            try {
                return blocking.withDeadlineAfter(options.longDeadlineMs(), MILLISECONDS)
                        .psiExchange(PSIRequest.getDefaultInstance());
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() == io.grpc.Status.Code.UNIMPLEMENTED) {
                    // A pre-PSI transport only offers downloadBundle(id), which needs per-client encrypted ids
                    // the dropbox cannot derive (R2 §4.3 fallback). Nothing to download from it.
                    error("carrier does not implement psiExchange; download-all impossible");
                    return null;
                }
                rpcFailed(e.getStatus(), "psiExchange");
                return null;
            }
        }

        /** Streams index {@code i} into staging, names it from {@code bundle.id}, then commits or discards it. */
        private IndexOutcome downloadIndex(BundleExchangeServiceGrpc.BundleExchangeServiceBlockingStub blocking,
                                           String sessionId,
                                           int index) {
            Staged staged;
            try {
                staged = store.beginStaging(Direction.DOWNSTREAM);
            } catch (IOException e) {
                throw new CarrierLost("cannot create staging file: " + e.getMessage());
            }
            long bytes = 0;
            // The blocking server-streaming iterator cannot be cancelled directly; run it in a cancellable context
            // so a local write failure does not leave the call open until its deadline.
            Context.CancellableContext context = Context.current().withCancellation();
            Context previous = context.attach();
            try (OutputStream out = staged.open()) {
                Iterator<BundleDownloadResponse> chunks = blocking.withDeadlineAfter(options.longDeadlineMs(),
                                                                                     MILLISECONDS)
                        .psiDownloadBundle(PSIDownloadRequest.newBuilder()
                                                   .setSessionId(sessionId)
                                                   .setTransportIndex(index)
                                                   .build());
                while (chunks.hasNext()) {
                    ByteString chunk = chunks.next().getChunk().getChunk();
                    chunk.writeTo(out);
                    bytes += chunk.size();
                }
            } catch (StatusRuntimeException e) {
                store.abort(staged);
                io.grpc.Status.Code code = e.getStatus().getCode();
                if (code == io.grpc.Status.Code.NOT_FOUND || code == io.grpc.Status.Code.INVALID_ARGUMENT) {
                    // The carrier's session expired or the file vanished (T<->S sync deleted it); not a failure of
                    // the dropbox, but the listing is different now.
                    logger.log(INFO, "index " + index + " gone on carrier: " + e.getStatus());
                    return new IndexOutcome(null, true);
                }
                rpcFailed(e.getStatus(), "psiDownloadBundle(" + index + ")");
                return new IndexOutcome(null, false);
            } catch (IOException e) {
                store.abort(staged);
                error("cannot write staged download: " + e.getMessage());
                return new IndexOutcome(null, false);
            } finally {
                context.detach(previous);
                context.cancel(null);
            }

            if (bytes == 0) {
                store.abort(staged);
                error("carrier sent an empty bundle at index " + index);
                return new IndexOutcome(null, false);
            }
            String bundleId = readJarBundleId(staged.path());
            if (bundleId == null) {
                store.abort(staged);
                error("download at index " + index + " is not a bundle jar with a clean bundle.id");
                return new IndexOutcome(null, false);
            }
            if (store.contains(Direction.DOWNSTREAM, bundleId)) {
                // Expected and frequent: the server re-offers the same bundle until superseded, and the carrier
                // keeps it after serving (R2 §6, R4 §4.2). Same id means same bytes, so keep the existing file.
                store.abort(staged);
                skipped++;
                return new IndexOutcome(bundleId, false);
            }
            try {
                // null claimed id: the store derives the id from bundle.id itself (single source of truth).
                store.commit(staged, null);
                downloaded++;
                logger.log(INFO, "downloaded " + bundleId + " (" + bytes + " B) from " + carrierId);
            } catch (IOException e) {
                error("commit of " + bundleId + " failed: " + e.getMessage()); // the store deleted the staged file
            }
            return new IndexOutcome(bundleId, false);
        }

        // ---------------------------------------------------------------- helpers

        /** Between-bundle check: cancel, interrupt, or the cycle's {@code keepGoing} turning false. */
        private void checkContinue() {
            checkCancelled();
            if (!keepGoing.getAsBoolean()) throw new VisitStopped("keepGoing is false (dwell expired)");
        }

        private void checkCancelled() {
            if (cancelRequested) throw new VisitStopped("cancelled");
            if (Thread.currentThread().isInterrupted()) throw new VisitStopped("interrupted");
        }

        /**
         * Classifies an RPC failure: cancel/interrupt end the visit as "stopped early"; a lost connection ends it
         * with an error; anything else is counted and the visit moves on to the next bundle.
         */
        private void rpcFailed(io.grpc.Status status, String what) {
            checkCancelled();
            String message = what + " failed: " + status.getCode() +
                    (status.getDescription() == null ? "" : " " + status.getDescription());
            io.grpc.Status.Code code = status.getCode();
            if (code == io.grpc.Status.Code.UNAVAILABLE || code == io.grpc.Status.Code.CANCELLED) {
                throw new CarrierLost(message);
            }
            error(message);
        }

        private void error(String message) {
            errors++;
            lastError = message;
            logger.log(WARNING, "visit " + carrierId + ": " + message);
        }
    }

    /** Result of one index download: the id read from the jar (if any) and whether the listing changed. */
    private static final class IndexOutcome {
        final String bundleId;
        final boolean listingChanged;

        IndexOutcome(String bundleId, boolean listingChanged) {
            this.bundleId = bundleId;
            this.listingChanged = listingChanged;
        }
    }
}
