package net.discdd.bundledropbox.rpc;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.TlsServerCredentials;
import io.grpc.okhttp.OkHttpServerBuilder;
import net.discdd.bundledropbox.cycle.ActivityGate;
import net.discdd.bundledropbox.cycle.Clock;
import net.discdd.bundledropbox.store.DeliveryLog;
import net.discdd.bundledropbox.store.RecencyBlobStore;
import net.discdd.bundledropbox.store.RelayStore;
import net.discdd.tls.DDDTLSUtil;
import net.discdd.tls.GrpcSecurityKey;

import javax.net.ssl.KeyManager;
import java.io.IOException;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The dropbox's client-facing gRPC server, running only while the dropbox is in HOST mode. To an
 * unmodified BundleClient it is indistinguishable from BundleTransport's {@code RpcServer}, which it is
 * adapted from (Research/R1 §2.1, §6):
 * <ul>
 *   <li>OkHttp server on port {@value #PORT}; the stock client hard-codes 7777 and connects to the Wi-Fi
 *       Direct group owner address (Research/R3 §1e).</li>
 *   <li>Server-auth-only TLS with the dropbox's own {@link GrpcSecurityKey} (type {@code SecurityUtils.SERVER}).
 *       The client accepts any single self-signed EC cert whose CN is {@code publicKeyToName(pub)}, with no
 *       pinning and no CA (Research/R3 §4). Client certificates are not requested, as on the transport.</li>
 *   <li>Max inbound message 5 MB: the client uploads in 4,000,000-byte chunks.</li>
 *   <li>Fixed pool of 4 executor threads, as on the transport.</li>
 * </ul>
 *
 * <p>Interceptor chain (outermost first): {@link RemoteAddrInterceptor} puts the client IP in the context,
 * {@link AdmissionInterceptor} admits it through the {@link ClientSessionTracker} / cycle
 * {@link ActivityGate} (or refuses with UNAVAILABLE while draining), then {@link DropboxExchangeService}
 * does the work against the {@link RelayStore}.
 *
 * <p>Lifecycle: the role cycle's radio driver calls {@link #start()} once the Wi-Fi Direct group is up and
 * {@link #stop()} before removing the group. A gRPC {@link Server} cannot be restarted, so every
 * {@code start()} builds a fresh one. Both are idempotent and {@code synchronized}. {@code stop()} blocks
 * for up to about {@value #STOP_GRACE_MS} + {@value #STOP_FORCE_MS} ms, so do not call it on the cycle's
 * state thread.
 *
 * <p>FUTURE: a dropbox control endpoint with its own keys (so the server can classify the dropbox and send
 * it control ADUs) belongs in a new {@code control/} package and must not change what this server shows to
 * stock clients. FUTURE: gRPC-over-internet fallback would reuse {@link DropboxExchangeService} unchanged.
 */
public final class DropboxRpcServer {
    private static final Logger logger = Logger.getLogger(DropboxRpcServer.class.getName());

    /** Port the stock BundleClient always dials (hard-coded in {@code BundleClientService}). */
    public static final int PORT = 7777;
    /** Same as the transport: enough for the client's 4,000,000-byte upload chunks. */
    public static final int MAX_INBOUND_MESSAGE_SIZE = 5 * 1024 * 1024;
    /** Executor size, same as the transport. */
    public static final int EXECUTOR_THREADS = 4;
    /** Graceful shutdown wait before {@code shutdownNow()}. */
    public static final long STOP_GRACE_MS = 2_000;
    /** Wait after {@code shutdownNow()}. */
    public static final long STOP_FORCE_MS = 1_000;
    /** How often idle client sessions are reaped. */
    public static final long REAP_PERIOD_MS = 1_000;

    private final KeyPair keyPair;
    private final X509Certificate cert;
    private final int port;
    private final DropboxExchangeService service;
    private final ClientSessionTracker tracker;
    private final AdmissionInterceptor admission;
    private final RemoteAddrInterceptor remoteAddr = new RemoteAddrInterceptor();

    // guarded by this
    private Server server;
    private ExecutorService executor;
    private ScheduledExecutorService reaper;

    /**
     * Production constructor: port {@value #PORT}, 10 s session idle timeout, wall clock from
     * {@link System#currentTimeMillis()}.
     *
     * @param grpcKeys     the dropbox's TLS identity ({@code new GrpcSecurityKey(paths.grpcSecurity, SecurityUtils.SERVER)})
     * @param store        relay store
     * @param recencyBlobs cached carrier recency blob
     * @param deliveryLog  served-bundle log
     * @param gate         the cycle's gate ({@code RoleCycleController.gate()})
     * @param clock        monotonic clock (the cycle's clock, {@code SystemClock::elapsedRealtime})
     * @param listener     status events; may be {@code null}
     */
    public DropboxRpcServer(GrpcSecurityKey grpcKeys,
                            RelayStore store,
                            RecencyBlobStore recencyBlobs,
                            DeliveryLog deliveryLog,
                            ActivityGate gate,
                            Clock clock,
                            RpcEventListener listener) {
        this(grpcKeys.grpcKeyPair, grpcKeys.grpcCert, PORT, store, recencyBlobs, deliveryLog, gate, clock,
             ClientSessionTracker.DEFAULT_IDLE_TIMEOUT_MS, listener);
    }

    /**
     * Full constructor (tests use port 0 and short idle timeouts).
     *
     * @param keyPair       TLS key pair
     * @param cert          self-signed cert for {@code keyPair} with CN {@code publicKeyToName(pub)}
     * @param port          listen port; 0 picks a free port (see {@link #getPort()})
     * @param store         relay store
     * @param recencyBlobs  cached carrier recency blob
     * @param deliveryLog   served-bundle log
     * @param gate          the cycle's gate
     * @param clock         monotonic clock
     * @param idleTimeoutMs client session idle timeout
     * @param listener      status events; may be {@code null}
     */
    public DropboxRpcServer(KeyPair keyPair,
                            X509Certificate cert,
                            int port,
                            RelayStore store,
                            RecencyBlobStore recencyBlobs,
                            DeliveryLog deliveryLog,
                            ActivityGate gate,
                            Clock clock,
                            long idleTimeoutMs,
                            RpcEventListener listener) {
        if (keyPair == null || cert == null) throw new NullPointerException("keyPair/cert");
        RpcEventListener l = listener == null ? RpcEventListener.NO_OP : listener;
        this.keyPair = keyPair;
        this.cert = cert;
        this.port = port;
        this.service = new DropboxExchangeService(store, recencyBlobs, deliveryLog, l, clock,
                                                  System::currentTimeMillis);
        this.tracker = new ClientSessionTracker(gate, clock, idleTimeoutMs, l);
        this.admission = new AdmissionInterceptor(tracker, l);
    }

    /**
     * Starts listening. No-op if already running.
     *
     * @throws IOException if TLS setup fails or the port cannot be bound (e.g. a previous server has not
     *                     released it yet); the caller should report the hosting attempt as failed
     */
    public synchronized void start() throws IOException {
        if (server != null) return;
        KeyManager[] keyManagers;
        try {
            keyManagers = DDDTLSUtil.getKeyManagerFactory(keyPair, cert).getKeyManagers();
        } catch (Exception e) {
            throw new IOException("Cannot build TLS key managers", e);
        }
        // Server-only TLS: no trust manager and no client auth, exactly like the transport.
        var credentials = TlsServerCredentials.newBuilder().keyManager(keyManagers).build();
        ExecutorService exec = Executors.newFixedThreadPool(EXECUTOR_THREADS, namedThreads("dropbox-rpc"));
        // ServerInterceptors.intercept runs the LAST interceptor first: remoteAddr, then admission.
        Server s = OkHttpServerBuilder.forPort(port, credentials)
                .maxInboundMessageSize(MAX_INBOUND_MESSAGE_SIZE)
                .addService(ServerInterceptors.intercept(service, admission, remoteAddr))
                .executor(exec)
                .build();
        try {
            s.start();
        } catch (IOException | RuntimeException e) {
            s.shutdownNow();
            exec.shutdownNow();
            throw e;
        }
        ScheduledExecutorService r = Executors.newSingleThreadScheduledExecutor(namedThreads("dropbox-rpc-reaper"));
        r.scheduleWithFixedDelay(this::reapSafely, REAP_PERIOD_MS, REAP_PERIOD_MS, TimeUnit.MILLISECONDS);
        server = s;
        executor = exec;
        reaper = r;
        logger.log(Level.INFO, "Dropbox rpc server listening on port " + s.getPort());
    }

    /**
     * Stops the server: graceful {@code shutdown()} for {@value #STOP_GRACE_MS} ms, then {@code shutdownNow()}
     * (cancels in-flight calls; partial uploads are aborted from staging, so nothing truncated is ever
     * committed). Then ends all client sessions (releasing their gate tokens) and drops PSI sessions.
     * No-op if not running. Blocks briefly.
     */
    public synchronized void stop() {
        if (server == null) return;
        logger.log(Level.INFO, "Stopping dropbox rpc server");
        Server s = server;
        server = null;
        try {
            s.shutdown();
            if (!s.awaitTermination(STOP_GRACE_MS, TimeUnit.MILLISECONDS)) {
                logger.log(Level.INFO, "Rpc server still busy after grace period; forcing shutdown");
                s.shutdownNow();
                if (!s.awaitTermination(STOP_FORCE_MS, TimeUnit.MILLISECONDS)) {
                    logger.log(Level.WARNING, "Rpc server did not terminate after shutdownNow");
                }
            }
        } catch (InterruptedException e) {
            s.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            reaper.shutdownNow();
            executor.shutdownNow();
            reaper = null;
            executor = null;
            tracker.closeAll();
            service.clearPsiSessions();
        }
    }

    /** @return true between a successful {@link #start()} and {@link #stop()}. */
    public synchronized boolean isRunning() {
        return server != null && !server.isShutdown();
    }

    /** @return the bound port while running (useful with port 0), else the configured port. */
    public synchronized int getPort() {
        return server != null ? server.getPort() : port;
    }

    /** @return number of admitted client sessions (for status). */
    public int activeSessions() {
        return tracker.activeSessions();
    }

    /** @return the session tracker (exposed for diagnostics and tests). */
    public ClientSessionTracker sessionTracker() {
        return tracker;
    }

    /** @return the exchange service (exposed for diagnostics and tests). */
    public DropboxExchangeService exchangeService() {
        return service;
    }

    private void reapSafely() {
        try {
            tracker.reapIdle();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Session reaper failed", e);
        }
    }

    private static ThreadFactory namedThreads(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
