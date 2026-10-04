package net.discdd.bundledropbox.visit;

import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
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
 *   <li><b>{@code getRecencyBlob}</b>, offered verbatim to the {@link RecencyBlobStore}, which keeps it only if
 *       the dropbox's clients would accept it (pinned server key, valid signature, not from the future). This
 *       always runs, even if {@code keepGoing} is already false: it is cheap and it is the only way a fresh
 *       dropbox can ever host (the stock client aborts the whole exchange without a SUCCESS, server-signed
 *       recency blob, R3/R4 §4.4).</li>
 *   <li><b>Upload</b> every UPSTREAM bundle not yet in the {@link ForwardLedger} for this carrier, newest first.
 *       Upload goes before download so the most valuable direction (client mail toward the internet) gets the
 *       radio time if the transport dwell is short (R2 §8).</li>
 *   <li><b>Download-all</b> of the carrier's client-bound bundles via repeated PSI sessions (R2 §4.3), committing
 *       each new one to DOWNSTREAM under the id read from the jar's {@code bundle.id}, within the DOWNSTREAM
 *       quota (checked per chunk). An id already held is {@linkplain RelayStore#touch touched} instead, which
 *       keeps a client's still-current bundle in the HOST PSI snapshot. Each visit to a carrier resumes at the
 *       index where the previous visit to it stopped, so a listing too long for one dwell is still covered over
 *       several visits.</li>
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
 * A caller that can be cancelled before its visit even starts (the service's gate token, force-released by the
 * drain cap) passes its own {@code cancelled} signal to {@link #visit(InetAddress, int, String, BooleanSupplier,
 * BooleanSupplier)}; that signal is never reset, so such an early cancel cannot be lost.
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

    /** Longest single sleep while waiting for upload flow control, so a caller-side cancel is noticed. */
    static final long READY_WAIT_SLICE_MS = 100;

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
     * Per carrier: the transport index the next download-all pass starts at. The carrier lists its files in
     * {@code File.listFiles()} order, which is stable while its directory is unchanged, and every index costs a
     * fresh {@code psiExchange} (N modPows on the carrier). Always starting at 0 would replay the same prefix on
     * every visit and never reach the tail of a long listing within one dwell. In memory only: after a restart
     * the dropbox simply starts at 0 again. FUTURE: PSI known-ID skip makes this mostly unnecessary.
     */
    private final Map<String, Integer> nextDownloadIndex = new ConcurrentHashMap<>();

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
        return visit(host, port, carrierId, keepGoing, () -> false);
    }

    /**
     * {@link #visit(InetAddress, int, String, BooleanSupplier)} with a caller-owned hard-cancel signal.
     *
     * @param cancelled checked wherever {@link #cancel()} is honored; true stops the visit at once. Unlike the
     *                  internal flag it is not reset when the visit starts, so a cancel that arrives between the
     *                  caller taking its gate token and this call is not lost (wire it to
     *                  {@code token::isCancelled})
     */
    public VisitResult visit(InetAddress host,
                             int port,
                             String carrierId,
                             BooleanSupplier keepGoing,
                             BooleanSupplier cancelled) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(carrierId, "carrierId");
        Objects.requireNonNull(keepGoing, "keepGoing");
        Objects.requireNonNull(cancelled, "cancelled");
        synchronized (visitLock) {
            cancelRequested = false;
            return new Visit(host, port, carrierId, keepGoing, cancelled).run();
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
        private final BooleanSupplier cancelled;

        private boolean reachable;
        private boolean recencyFetched;
        private boolean recencyUpdated;
        private int uploaded;
        private int downloaded;
        private int skipped;
        private int errors;
        private boolean stoppedEarly;
        private String lastError;

        Visit(InetAddress host, int port, String carrierId, BooleanSupplier keepGoing, BooleanSupplier cancelled) {
            this.host = host;
            this.port = port;
            this.carrierId = carrierId;
            this.keepGoing = keepGoing;
            this.cancelled = cancelled;
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
                checkCancelled();
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
         * if the dropbox's clients would accept it and it is strictly newer. A carrier that never synced with the
         * server answers with the default instance (status UNDEFINED), which is normal and not an error.
         *
         * <p>{@code recencyFetched} means "this carrier holds a blob our clients trust", not merely SUCCESS: a
         * carrier synced to another deployment's server must not count as in sync (the service rotates the
         * advertised id and ends a bootstrap VISIT early on it, Audit M5).
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
                byte[] raw = response.toByteArray();
                recencyFetched = recencyStore.isTrusted(raw);
                recencyUpdated = recencyStore.offer(raw);
                if (response.getStatus() == RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS && !recencyFetched) {
                    logger.log(WARNING, "carrier " + carrierId + " serves a recency blob our clients would reject " +
                            "(another server, bad signature or future timestamp); not relayed");
                }
                logger.log(INFO, "recency blob ts=" + response.getRecencyBlob().getBlobTimestamp() + " stored=" +
                        recencyUpdated);
            } catch (StatusRuntimeException e) {
                rpcFailed(e.getStatus(), "getRecencyBlob");
            }
        }

        // ---------------------------------------------------------------- upload

        /**
         * Uploads every committed UPSTREAM bundle this carrier has not acknowledged yet, <b>newest first</b>. The
         * ledger is updated only after a SUCCESS response, so failures are retried on the next visit.
         *
         * <p>Why newest first: the ledger is per carrier and UPSTREAM is never pruned in v1, so the first visit to
         * a new carrier re-offers the whole history. The server ignores bundles whose counter is at or below the
         * last one it received from that client ({@code ServerBundleTransmission}, R4), so each client's newest
         * bundle is the one that matters and must not be starved by stale ones when the dwell is short.
         * FUTURE: server ACKs (or keep/delete control ADUs) let the dropbox skip superseded UPSTREAM bundles
         * altogether instead of merely ordering them last. The carrier stores each upload
         * verbatim as {@code BundleTransmission/server/<id>} and forwards it on its next server sync; it does no
         * identity or sender-type checks (R2 §3), so relaying other clients' bundles works unmodified.
         *
         * <p>FUTURE: dropbox control endpoint — also upload the dropbox's own control bundle (client-ACK reports
         * from {@code DeliveryLog}, holdings) signed with its own keys, so the server can classify it as a dropbox
         * and answer with keep/delete control ADUs (R4 §5 items 1 and 3).
         */
        private void uploadAll(BundleExchangeServiceGrpc.BundleExchangeServiceStub async) {
            List<BundleRecord> upstream = new ArrayList<>(store.list(Direction.UPSTREAM));
            Collections.reverse(upstream); // list() is oldest first
            for (BundleRecord record : upstream) {
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
            // Released by gRPC's onReady callback and by the call ending, so awaitReady sleeps instead of polling.
            Semaphore readySignal = new Semaphore(0);
            ClientResponseObserver<BundleUploadRequest, BundleUploadResponse> responseObserver =
                    new ClientResponseObserver<>() {
                        @Override
                        public void beforeStart(ClientCallStreamObserver<BundleUploadRequest> requestStream) {
                            // Must be registered before the call starts (gRPC contract for setOnReadyHandler).
                            requestStream.setOnReadyHandler(readySignal::release);
                        }

                        @Override
                        public void onNext(BundleUploadResponse value) {
                            response.complete(value);
                            readySignal.release();
                        }

                        @Override
                        public void onError(Throwable t) {
                            response.completeExceptionally(t);
                            readySignal.release();
                        }

                        @Override
                        public void onCompleted() {
                            response.complete(null);
                            readySignal.release();
                        }
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
                    if (!awaitReady(requests, response, readySignal)) break;
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
         * <p>Event driven: {@code readySignal} is released by gRPC's onReady handler and when the call ends, so
         * the worker sleeps while the carrier drains the window instead of polling {@code isReady()} every few
         * milliseconds for the whole upload (Audit i5). The wait still wakes every {@value #READY_WAIT_SLICE_MS}
         * ms because the caller's cancel signal ({@code token::isCancelled}) cannot notify; {@link #cancel()}
         * itself also ends the call, which releases the signal at once.
         *
         * @return false if the call already finished (error) and nothing more should be sent
         */
        private boolean awaitReady(ClientCallStreamObserver<BundleUploadRequest> requests,
                                   CompletableFuture<BundleUploadResponse> response,
                                   Semaphore readySignal) {
            long giveUpAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.longDeadlineMs());
            while (!requests.isReady()) {
                if (response.isDone()) return false;
                checkCancelled();
                if (System.nanoTime() > giveUpAt) return true; // let the RPC deadline report the failure
                try {
                    // A permit left over from an earlier onReady only causes one extra isReady() check.
                    readySignal.tryAcquire(READY_WAIT_SLICE_MS, MILLISECONDS);
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
         * <p>Resume: a pass walks the indices cyclically from {@link #nextDownloadIndex} for this carrier and
         * records the index after each one attempted, so when the dwell (or the drain cap) cuts a visit short,
         * the next visit continues with the indices this one never reached instead of replaying the same prefix.
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
                int start = n == 0 ? 0 : Math.floorMod(nextDownloadIndex.getOrDefault(carrierId, 0), n);
                if (start != 0) logger.log(INFO, "resuming downloads from " + carrierId + " at index " + start);
                for (int k = 0; k < n; k++) {
                    int i = (start + k) % n;
                    checkContinue();
                    if (!store.hasCapacityFor(Direction.DOWNSTREAM, 0)) {
                        // DOWNSTREAM has its own quota below the total capacity, so a full DOWNSTREAM never
                        // blocks client uploads (Audit M10).
                        error("downstream quota full; stopping downloads");
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
                    if (outcome == IndexOutcome.STOP) return; // out of space; retried on a later visit
                    nextDownloadIndex.put(carrierId, i + 1); // floorMod'd against the next listing size
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

        /**
         * Streams index {@code i} into staging, names it from {@code bundle.id}, then commits or discards it.
         *
         * <p>The DOWNSTREAM quota and {@link RelayStore#MAX_BUNDLE_BYTES} are enforced per chunk, so one carrier
         * bundle cannot overshoot the capacity or fill the device (Audit m7). A full quota stops the pass at this
         * index (retried on a later visit); an oversized bundle is skipped as an error. An empty stream or a file that is
         * not a bundle jar counts as skipped, not as an error: a stock transport creates the client-bound file
         * before its own server download and leaves it empty or partial if that fails, and the next good sync
         * replaces it (Audit m6), so it must not make the whole visit "failed".
         */
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
                    bytes += chunk.size();
                    if (bytes > RelayStore.MAX_BUNDLE_BYTES) {
                        // Never storable: skip it (the resume index moves past it) rather than stopping, or every
                        // later visit would restart at this index and never reach the rest of the listing.
                        store.abort(staged);
                        context.cancel(null); // ends the server stream now instead of at its deadline
                        error("bundle at index " + index + " exceeds " + RelayStore.MAX_BUNDLE_BYTES + " B; skipped");
                        return new IndexOutcome(null, false);
                    }
                    // The staged bytes written so far already count as used; add only this chunk.
                    if (!store.hasCapacityFor(Direction.DOWNSTREAM, chunk.size())) {
                        store.abort(staged);
                        context.cancel(null);
                        error("bundle at index " + index + " does not fit the downstream quota (" + bytes +
                                      " B so far); stopping downloads");
                        return IndexOutcome.STOP;
                    }
                    chunk.writeTo(out);
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
                skipped++;
                logger.log(INFO, "carrier file at index " + index + " is empty (its own server download failed?); " +
                        "skipped");
                return new IndexOutcome(null, false);
            }
            String bundleId = readJarBundleId(staged.path());
            if (bundleId == null) {
                store.abort(staged);
                skipped++;
                logger.log(INFO, "carrier file at index " + index + " is not a bundle jar with a clean bundle.id " +
                        "(truncated?); skipped");
                return new IndexOutcome(null, false);
            }
            if (store.contains(Direction.DOWNSTREAM, bundleId)) {
                // Expected and frequent: the server re-offers the same bundle until superseded, and the carrier
                // keeps it after serving (R2 §6, R4 §4.2). Same id means same bytes, so keep the existing file,
                // but record that the server still considers it current (keeps it in the PSI snapshot, Audit M9).
                store.abort(staged);
                store.touch(Direction.DOWNSTREAM, bundleId);
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
            if (cancelRequested || cancelled.getAsBoolean()) throw new VisitStopped("cancelled");
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
        /** Downloads must stop (no space); the index is retried on a later visit. */
        static final IndexOutcome STOP = new IndexOutcome(null, false);

        final String bundleId;
        final boolean listingChanged;

        IndexOutcome(String bundleId, boolean listingChanged) {
            this.bundleId = bundleId;
            this.listingChanged = listingChanged;
        }
    }
}
