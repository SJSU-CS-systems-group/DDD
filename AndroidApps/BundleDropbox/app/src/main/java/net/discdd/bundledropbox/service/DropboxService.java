package net.discdd.bundledropbox.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Base64;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import net.discdd.bundledropbox.BundleDropboxActivity;
import net.discdd.bundledropbox.DropboxControls;
import net.discdd.bundledropbox.DropboxPrefs;
import net.discdd.bundledropbox.DropboxStatusRepository;
import net.discdd.bundledropbox.DropboxWifiState;
import net.discdd.bundledropbox.R;
import net.discdd.bundledropbox.cycle.ActivityGate;
import net.discdd.bundledropbox.cycle.Clock;
import net.discdd.bundledropbox.cycle.CycleListener;
import net.discdd.bundledropbox.cycle.CycleMode;
import net.discdd.bundledropbox.cycle.RoleCycleController;
import net.discdd.bundledropbox.rpc.DropboxRpcServer;
import net.discdd.bundledropbox.rpc.RpcEventListener;
import net.discdd.bundledropbox.status.DropboxStatus;
import net.discdd.bundledropbox.store.BundleRecord;
import net.discdd.bundledropbox.store.Direction;
import net.discdd.bundledropbox.store.DropboxPaths;
import net.discdd.bundledropbox.store.FileDeliveryLog;
import net.discdd.bundledropbox.store.FileRecencyBlobStore;
import net.discdd.bundledropbox.store.FileRelayStore;
import net.discdd.bundledropbox.store.JsonForwardLedger;
import net.discdd.bundledropbox.visit.TransportVisitClient;
import net.discdd.bundledropbox.visit.VisitResult;
import net.discdd.bundledropbox.wifi.DropboxWifiStateBridge;
import net.discdd.bundledropbox.wifi.HostingEndpoint;
import net.discdd.bundledropbox.wifi.WifiDirectRadioDriver;
import net.discdd.screens.LogFragment;
import net.discdd.utils.UserLogRepository;
import net.discdd.utils.UserLogRepository.UserLogType;

import java.io.File;
import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The dropbox runtime: a started, sticky foreground service that wires every module together and keeps
 * the oscillating Wi-Fi Direct role cycle running with the screen off (02 Decisions: always plugged in,
 * foreground service, optional keep-awake). Lifecycle adapted from {@code BundleTransportService}.
 *
 * <h2>Wiring (03 Architecture)</h2>
 * <pre>
 *  DropboxPaths(root) ─┬─ FileRelayStore (upstream/ downstream/ staging/) ──┬─ DropboxRpcServer (HOST, :7777)
 *                      ├─ FileRecencyBlobStore (meta/recencyBlob.bin) ──────┤
 *                      ├─ JsonForwardLedger (meta/forwarded.json) ──────────┼─ TransportVisitClient (VISIT)
 *                      ├─ FileDeliveryLog (meta/delivery-log.jsonl) ────────┘
 *                      └─ DropboxIdentity (GrpcSecurity/ keys, baseId, advertised-id generation)
 *
 *  RoleCycleController ── RadioDriver ──▶ WifiDirectRadioDriver
 *        │ gate()                             ├─ DropboxWifiHost   ── HostingEndpoint ──▶ DropboxRpcServer
 *        ▼                                    └─ DropboxWifiJoiner ── VisitRunner ──────▶ {@link #runVisit}
 *   ActivityGate ◀── ClientSessionTracker (one token per client IP) / visit worker (one token per visit)
 *
 *  Status:  CycleListener, RpcEventListener, visit results ──▶ DropboxStatusRepository (UI Status/Storage)
 *           WifiRadioState ── DropboxWifiStateBridge ──▶ DropboxWifiState (UI Local Wifi)
 * </pre>
 *
 * <h2>Threading</h2>
 * <ul>
 *   <li>Main thread: Android lifecycle callbacks, SharedPreferences listener, all {@code WifiP2pManager}
 *       work (inside the radio driver).</li>
 *   <li>{@code dropbox-service}: one thread for initialization (disk I/O, key generation) and teardown.</li>
 *   <li>{@code dropbox-cycle}: the controller's owner thread; {@link CycleListener} callbacks.</li>
 *   <li>{@code dropbox-rpc-*}: client RPCs; {@link RpcEventListener} callbacks.</li>
 *   <li>{@code dropbox-wifi-worker-*}: {@link HostingEndpoint} calls and {@link #runVisit} (blocking).</li>
 *   <li>UI controls ({@link DropboxControls}) arrive on Kotlin {@code Dispatchers.IO}.</li>
 * </ul>
 * Every component is thread-safe; status is published with the repository's atomic
 * {@code modify}, reading all sources inside the update function so the newest snapshot always wins.
 */
public class DropboxService extends Service implements DropboxControls {
    private static final Logger logger = Logger.getLogger(DropboxService.class.getName());

    /** Notification channel id (the transport uses "DDD-Transport"). */
    public static final String NOTIFICATION_CHANNEL_ID = "DDD-Dropbox";
    private static final int NOTIFICATION_ID = 1;
    /** ActivityGate key of the visit worker's token (client sessions are keyed by IP, so no clash). */
    static final String VISIT_GATE_KEY = "visit";
    /** How long a UI control waits for initialization to finish before reporting "not ready". */
    private static final long READY_WAIT_MS = 15_000;
    private static final String WAKE_TAG = "DDDDropbox:keepAwake";

    private final IBinder binder = new LocalBinder();
    private final ExecutorService serviceExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dropbox-service");
        t.setDaemon(true);
        return t;
    });
    /** Completes when every component is constructed and the cycle has been started. */
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    /** Monotonic clock for the cycle and session tracker (deadlines survive wall-clock changes). */
    private final Clock clock = SystemClock::elapsedRealtime;

    private boolean started; // main thread only: makes onStartCommand idempotent (START_STICKY redelivery)
    private volatile boolean destroyed;

    // Components, written once on dropbox-service before `ready` completes (volatile: read from callbacks).
    private volatile SharedPreferences prefs;
    private volatile DropboxIdentity identity;
    private volatile FileRelayStore store;
    private volatile FileRecencyBlobStore recency;
    private volatile JsonForwardLedger ledger;
    private volatile DropboxRpcServer rpcServer;
    private volatile TransportVisitClient visitClient;
    private volatile WifiDirectRadioDriver radio;
    private volatile RoleCycleController controller;
    private volatile ActivityGate gate;

    // Keep-awake locks (main thread only).
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    /** Must be a field: SharedPreferences holds listeners weakly. Runs on the main thread. */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (p, key) -> onPrefChanged(key);

    // ------------------------------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        logger.info("DropboxService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        // startForegroundService() requires startForeground() promptly on every start command, so this
        // runs synchronously each time; it is cheap and idempotent.
        startInForeground(getString(R.string.dropbox_notification_text));
        if (started) {
            // Already wired (activity re-created, or START_STICKY re-delivery): nothing to redo.
            return START_STICKY;
        }
        started = true;
        // Heavy work (key generation, directory scans, fsync) off the main thread, as in the transport.
        serviceExecutor.execute(this::initialize);
        // The dropbox is a stationary, always-plugged-in relay: restart it if the system kills it.
        return START_STICKY;
    }

    /**
     * Builds and starts everything. Order matters:
     * <ol>
     *   <li>Store first: {@code FileRelayStore}'s constructor runs {@code reconcile()}, which deletes
     *       leftover staging files, so it must finish before any RPC or visit can stage.</li>
     *   <li>The controller owns the {@link ActivityGate}; the RPC server and the visit worker share it.
     *       The radio driver is constructed before the controller (the controller needs it), and its
     *       {@code VisitRunner} reads {@link #gate} lazily, only once the cycle runs.</li>
     *   <li>The RPC server is started once for the service lifetime (like the transport's).</li>
     *   <li>{@code controller.start()} last.</li>
     * </ol>
     */
    private void initialize() {
        try {
            // The service has no LogFragment, but its JUL records should reach the Logs tab.
            LogFragment.registerLoggerHandler();
            SharedPreferences p = DropboxPrefs.get(this);
            prefs = p;

            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            DropboxPaths paths = new DropboxPaths(dir.toPath()).createDirectories();

            DropboxIdentity id = DropboxIdentity.load(paths,
                                                      b -> Base64.encodeToString(b, Base64.DEFAULT),
                                                      p.getBoolean(DropboxPrefs.KEY_ROTATE_ADVERTISED_ID,
                                                                   DropboxPrefs.DEFAULT_ROTATE_ADVERTISED_ID));
            identity = id;

            FileRelayStore relayStore = new FileRelayStore(paths, DropboxPrefs.maxStorageBytes(p));
            FileRecencyBlobStore recencyStore = new FileRecencyBlobStore(paths);
            JsonForwardLedger forwardLedger = new JsonForwardLedger(paths);
            FileDeliveryLog deliveryLog = new FileDeliveryLog(paths);
            forwardLedger.pruneAgainst(relayStore); // forget ledger rows of bundles cleared while we were down
            store = relayStore;
            recency = recencyStore;
            ledger = forwardLedger;

            visitClient = new TransportVisitClient(relayStore, forwardLedger, recencyStore, id.keys());

            WifiDirectRadioDriver driver = new WifiDirectRadioDriver(this,
                                                                     id.baseId(),
                                                                     id::advertisedId,
                                                                     () -> recencyStore.current().orElse(null),
                                                                     hostingEndpoint,
                                                                     this::runVisit);
            radio = driver;

            // Bootstrap rule: HOST only once a SUCCESS recency blob is cached (stock clients abort without one).
            RoleCycleController cycle = new RoleCycleController(driver,
                                                                clock,
                                                                () -> recencyStore.currentTimestamp() > 0,
                                                                DropboxPrefs.cycleConfig(p));
            controller = cycle;
            gate = cycle.gate();
            cycle.addListener(cycleListener);

            rpcServer = new DropboxRpcServer(id.keys(), relayStore, recencyStore, deliveryLog, cycle.gate(), clock,
                                             rpcListener);
            try {
                rpcServer.start();
            } catch (Exception e) {
                // Not fatal: HostingEndpoint.start() retries before every HOST role and fails that role cleanly.
                logUi(UserLogType.EXCHANGE, Level.WARNING, "Client server could not start yet: " + e.getMessage());
            }

            driver.initialize();
            driver.addStateListener(new DropboxWifiStateBridge());
            // UNAVAILABLE retries "after 30 s or when Wi-Fi/permission state changes" (03 Architecture).
            driver.setEnvironmentListener((available, reason) -> {
                logUi(UserLogType.WIFI, available ? Level.INFO : Level.WARNING, "Wi-Fi Direct: " + reason);
                if (available) cycle.retryNow();
            });

            getMainExecutor().execute(() -> {
                p.registerOnSharedPreferenceChangeListener(prefsListener);
                applyKeepAwake();
            });

            logUi(UserLogType.WIFI, Level.INFO, "Dropbox " + id.baseId() + " starting (advertising " +
                    id.advertisedId() + ", " + (recencyStore.currentTimestamp() > 0 ? "recency blob cached" :
                    "no recency blob yet: visiting transports first") + ")");
            refreshStatus();
            if (destroyed) {
                // onDestroy ran while we were initializing; its teardown task (queued behind us on this
                // executor) releases what was built. Do not start cycling a dead service.
                ready.completeExceptionally(new IllegalStateException("service destroyed during startup"));
                return;
            }
            cycle.start();
            ready.complete(null);
        } catch (Throwable e) {
            logger.log(Level.SEVERE, "DropboxService initialization failed", e);
            String msg = "Dropbox failed to start: " + e.getMessage();
            DropboxStatusRepository.modify(s -> s.toBuilder().lastError(msg).build());
            logUi(UserLogType.WIFI, Level.SEVERE, msg);
            ready.completeExceptionally(e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /**
     * Teardown: unhook preferences, stop the cycle (which drives the radio to idle: group removed or carrier
     * left), then release the radio, the RPC server and the locks. Radio teardown is asynchronous, so the rest
     * runs on {@code dropbox-service} after waiting (bounded) for the cycle to reach STOPPED.
     *
     * <p>The teardown task is queued on the same single-thread executor as {@link #initialize()}, so if the
     * service dies while still initializing, teardown runs after it and sees every component it built (it
     * reads the fields when it runs, not when it is queued).
     */
    @Override
    public void onDestroy() {
        logger.info("DropboxService destroyed");
        destroyed = true;
        SharedPreferences p = prefs;
        if (p != null) p.unregisterOnSharedPreferenceChangeListener(prefsListener);
        releaseKeepAwake();
        // Fast path when initialized: start the radio teardown and cut a running visit right away.
        RoleCycleController cycle = controller;
        if (cycle != null) cycle.shutdown();
        TransportVisitClient vc = visitClient;
        if (vc != null) vc.cancel();
        serviceExecutor.execute(this::teardown);
        serviceExecutor.shutdown();
        super.onDestroy();
    }

    /** Second half of {@link #onDestroy()}, on {@code dropbox-service}. */
    private void teardown() {
        RoleCycleController cycle = controller;
        if (cycle != null) {
            cycle.shutdown(); // idempotent: a second request is ignored or dropped after shutdown
            long deadline = SystemClock.elapsedRealtime() + RoleCycleController.SHUTDOWN_TIMEOUT_MS;
            while (cycle.mode() != CycleMode.STOPPED && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100);
            }
        }
        TransportVisitClient vc = visitClient;
        if (vc != null) vc.cancel();
        WifiDirectRadioDriver driver = radio;
        if (driver != null) driver.shutdown();
        DropboxRpcServer server = rpcServer;
        if (server != null) server.stop();
        DropboxWifiState.clearRoles();
        DropboxStatusRepository.modify(s -> s.toBuilder()
                .cycle(CycleMode.STOPPED, clock.nowMillis(), CycleListener.NO_DEADLINE)
                .activeSessions(0)
                .build());
        logger.info("DropboxService teardown complete");
    }

    // ------------------------------------------------------------------------------------------ wiring

    /**
     * The client-facing server the Wi-Fi host brackets each HOST role with. The server itself runs for the
     * whole service lifetime ({@link DropboxRpcServer} Javadoc), so {@code start()} only makes sure it is up
     * (retrying a failed bind) before the DNS-SD record is published (a stock client gives up after ~4 s of
     * failed probes, R3 §7), and {@code stop()} only ends the HOST role's client and PSI sessions.
     * Two abstract methods, hence an anonymous class rather than a lambda.
     */
    private final HostingEndpoint hostingEndpoint = new HostingEndpoint() {
        @Override
        public void start() throws Exception {
            DropboxRpcServer server = rpcServer;
            if (server == null) throw new IllegalStateException("client server not initialized");
            server.start();
        }

        @Override
        public void stop() {
            DropboxRpcServer server = rpcServer;
            if (server != null) server.endClientSessions();
        }
    };

    /**
     * {@code VisitRunner}: one exchange with a carrier the Wi-Fi joiner has joined. Runs on a driver worker
     * thread and blocks for the whole exchange.
     *
     * <p>Holds one {@link ActivityGate} token for the whole visit (03 Architecture "Visit-side keying"), so
     * a dwell that expires mid-exchange drains instead of cutting the transfer: {@code keepGoing} turns
     * false when admissions close, the client finishes the in-flight bundle and returns, the token closes
     * and the controller switches. If the drain cap passes first, the gate force-releases the token and its
     * cancel hook hard-cancels the gRPC channel; partial downloads are aborted from staging and nothing is
     * marked forwarded without the carrier's SUCCESS ack, so the work stays retryable.
     */
    private boolean runVisit(InetAddress carrierAddress, String carrierId, BooleanSupplier stopRequested) {
        ActivityGate g = gate;
        TransportVisitClient client = visitClient;
        if (g == null || client == null) return false;
        ActivityGate.Token token = g.tryAdmit(VISIT_GATE_KEY);
        if (token == null) {
            // The dwell ended between joining the carrier and starting the exchange.
            logger.info("Visit to " + carrierId + " skipped: admissions closed");
            return false;
        }
        token.setCancelHook(client::cancel);
        refreshStatus(); // activeSessions > 0 while VISITING = "Exchanging with transport"
        logUi(UserLogType.EXCHANGE, Level.INFO, "Exchanging with transport " + carrierId);
        try {
            VisitResult r = client.visit(carrierAddress,
                                         TransportVisitClient.TRANSPORT_PORT,
                                         carrierId,
                                         () -> !stopRequested.getAsBoolean() && !g.isClosing());
            onVisitResult(r);
            return r.ok();
        } finally {
            token.close(); // may fire the drain listener: VISIT_DRAINING ends now
            refreshStatus();
        }
    }

    /** Status, log and advertised-id rotation after a carrier exchange. */
    private void onVisitResult(VisitResult r) {
        DropboxIdentity id = identity;
        if (r.recencyFetched() && id != null && id.isRotating()) {
            // 03 Architecture §5: clients only exchange when blobTimestamp > their lastExchange for that
            // transportId (R3). A new id makes clients treat the refreshed dropbox as a new transport.
            String advertised = id.bumpGeneration();
            logUi(UserLogType.EXCHANGE, Level.INFO, "Next client advertisement uses id " + advertised);
        }
        String summary = "Transport " + r.carrierId() + ": " + (r.ok() ? "ok" : "incomplete") + ", uploaded " +
                r.uploaded() + ", downloaded " + r.downloaded() + ", already held " + r.skipped() +
                (r.recencyUpdated() ? ", newer recency blob stored" : "") +
                (r.lastError() == null ? "" : ", last error: " + r.lastError());
        logUi(UserLogType.EXCHANGE, r.ok() ? Level.INFO : Level.WARNING, summary);
        DropboxStatus.LastVisit last = new DropboxStatus.LastVisit(r.carrierId(), r.uploaded(), r.downloaded(),
                                                                   System.currentTimeMillis(), r.ok());
        String error = r.ok() ? null : r.lastError();
        DropboxStatusRepository.modify(s -> {
            DropboxStatus.Builder b = s.toBuilder().lastVisit(last);
            if (error != null) b.lastError("Visit: " + error);
            return b.build();
        });
        // FUTURE: once the server sends keep/delete control ADUs, apply them here via RetentionPolicy.
    }

    /** Role-cycle events (on {@code dropbox-cycle}). */
    private final CycleListener cycleListener = new CycleListener() {
        @Override
        public void onModeChanged(CycleMode previous, CycleMode current, long startedAt, long deadline) {
            refreshStatus();
            if (current == CycleMode.HOSTING || current == CycleMode.VISITING || current == CycleMode.STOPPED ||
                    current == CycleMode.UNAVAILABLE) {
                logUi(UserLogType.WIFI, current == CycleMode.UNAVAILABLE ? Level.WARNING : Level.INFO,
                      "Role: " + describe(current));
                getMainExecutor().execute(() -> {
                    if (!destroyed) updateNotification("Currently " + describe(current));
                });
            }
        }

        @Override
        public void onCycleError(CycleMode mode, String reason) {
            DropboxStatusRepository.modify(s -> s.toBuilder().lastError(reason).build());
            logUi(UserLogType.WIFI, Level.WARNING, "Radio error in " + mode + ": " + reason);
        }
    };

    /** Client-facing server events (on gRPC executor / reaper threads). */
    private final RpcEventListener rpcListener = new RpcEventListener() {
        @Override
        public void onActiveSessionsChanged(int activeSessions) {
            refreshStatus();
        }

        @Override
        public void onUploadCommitted(BundleRecord record) {
            logUi(UserLogType.EXCHANGE, Level.INFO, "Client deposited bundle " + shortId(record.bundleId()) + " (" +
                    record.sizeBytes() + " bytes)");
            refreshStatus();
        }

        @Override
        public void onUploadFailed(String claimedBundleId, String reason) {
            logUi(UserLogType.EXCHANGE, Level.WARNING, "Client upload " + shortId(claimedBundleId) + " failed: " +
                    reason);
            refreshStatus();
        }

        @Override
        public void onDownloadServed(String bundleId) {
            // FUTURE: the DeliveryLog entry written alongside this becomes a client-ACK report ADU.
            logUi(UserLogType.EXCHANGE, Level.INFO, "Client picked up bundle " + shortId(bundleId));
        }

        @Override
        public void onClientRejected(String remoteIp, String fullMethod) {
            logger.fine("Client " + remoteIp + " turned away while draining (" + fullMethod + ")");
        }
    };

    /**
     * Publishes a fresh {@link DropboxStatus}. All sources are read inside the update function, so a CAS
     * retry re-reads them and the newest values always win regardless of which thread called first. The UI
     * ticks its own countdown from the monotonic deadline, so nothing here runs on a timer.
     */
    private void refreshStatus() {
        DropboxStatusRepository.modify(s -> {
            DropboxStatus.Builder b = s.toBuilder();
            RoleCycleController cycle = controller;
            if (cycle != null) b.cycle(cycle.mode(), cycle.modeStartedAtMillis(), cycle.deadlineAtMillis());
            DropboxIdentity id = identity;
            if (id != null) b.advertisedId(id.advertisedId());
            FileRecencyBlobStore r = recency;
            if (r != null) b.recencyBlobTimestamp(r.currentTimestamp());
            ActivityGate g = gate;
            if (g != null) b.activeSessions(g.activeKeyCount());
            FileRelayStore st = store;
            if (st != null) b.storeStats(st.stats());
            return b.build();
        });
    }

    // ------------------------------------------------------------------------------------------ settings

    /** SharedPreferences change (main thread). {@code key == null} means the file was cleared. */
    private void onPrefChanged(String key) {
        SharedPreferences p = prefs;
        RoleCycleController cycle = controller;
        if (p == null || cycle == null || destroyed) return;
        boolean all = key == null;
        if (all || DropboxPrefs.KEY_CLIENT_PERIOD_S.equals(key) || DropboxPrefs.KEY_TRANSPORT_PERIOD_S.equals(key) ||
                DropboxPrefs.KEY_DRAIN_CAP_S.equals(key) || DropboxPrefs.KEY_RETURN_AFTER_VISIT.equals(key)) {
            cycle.updateConfig(DropboxPrefs.cycleConfig(p)); // applies at the next dwell
        }
        if (all || DropboxPrefs.KEY_MAX_STORAGE_MB.equals(key)) {
            store.setCapacityBytes(DropboxPrefs.maxStorageBytes(p));
            refreshStatus();
        }
        if (all || DropboxPrefs.KEY_ROTATE_ADVERTISED_ID.equals(key)) {
            identity.setRotate(p.getBoolean(DropboxPrefs.KEY_ROTATE_ADVERTISED_ID,
                                            DropboxPrefs.DEFAULT_ROTATE_ADVERTISED_ID));
            refreshStatus(); // takes effect in the TXT record at the next startHosting
        }
        if (all || DropboxPrefs.KEY_KEEP_AWAKE.equals(key)) {
            applyKeepAwake();
        }
    }

    /**
     * Optional keep-awake (02 Decisions): the cycle's executor delays pause in deep sleep, which would only
     * delay switches, but an unattended relay should keep cycling. Partial wake lock plus a low-latency Wi-Fi
     * lock while {@code keep_awake} is on. Main thread only.
     */
    private void applyKeepAwake() {
        SharedPreferences p = prefs;
        boolean want = p != null && !destroyed &&
                p.getBoolean(DropboxPrefs.KEY_KEEP_AWAKE, DropboxPrefs.DEFAULT_KEEP_AWAKE);
        if (!want) {
            releaseKeepAwake();
            return;
        }
        if (wakeLock == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG);
            wakeLock.setReferenceCounted(false);
        }
        if (wifiLock == null) {
            WifiManager wm = getSystemService(WifiManager.class);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WAKE_TAG);
            wifiLock.setReferenceCounted(false);
        }
        // Held for as long as the setting is on (released in onDestroy or when turned off): the relay is
        // assumed to be plugged in, so there is deliberately no timeout.
        if (!wakeLock.isHeld()) wakeLock.acquire();
        if (!wifiLock.isHeld()) wifiLock.acquire();
        logger.info("Keep-awake locks held");
    }

    private void releaseKeepAwake() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }

    // ------------------------------------------------------------------------------------------ UI controls

    @Override
    public void pauseCycle() {
        awaitReady();
        controller.pause();
        logUi(UserLogType.WIFI, Level.INFO, "Operator paused the role cycle");
    }

    @Override
    public void resumeCycle() {
        awaitReady();
        controller.resume();
        logUi(UserLogType.WIFI, Level.INFO, "Operator resumed the role cycle");
    }

    @Override
    public boolean isCyclePaused() {
        RoleCycleController cycle = controller;
        return cycle != null && cycle.isPaused();
    }

    @Override
    public void forceVisitNow() {
        awaitReady();
        controller.forceVisit();
    }

    @Override
    public void forceHostNow() {
        awaitReady();
        controller.forceHost();
    }

    @Override
    public void clearUpstream() {
        clear(Direction.UPSTREAM);
    }

    @Override
    public void clearDownstream() {
        clear(Direction.DOWNSTREAM);
    }

    /**
     * Operator "clear": delete every committed bundle of one direction, then drop ledger rows of bundles
     * that no longer exist. Staged (in-flight) files are untouched; they commit or abort on their own.
     * FUTURE: server keep/delete control ADUs (RetentionPolicy) replace this manual clear.
     */
    private void clear(Direction d) {
        awaitReady();
        int n = 0;
        for (BundleRecord r : store.list(d)) {
            store.delete(d, r.bundleId());
            n++;
        }
        ledger.pruneAgainst(store);
        refreshStatus();
        logUi(UserLogType.EXCHANGE, Level.INFO, "Operator cleared " + n + " " +
                (d == Direction.UPSTREAM ? "deposited (upstream)" : "pickup (downstream)") + " bundles");
    }

    @Override
    public void wifiPermissionGranted() {
        awaitReady();
        radio.wifiPermissionGranted();
        controller.retryNow(); // leave UNAVAILABLE now instead of after 30 s
    }

    /**
     * Blocks (UI calls arrive on {@code Dispatchers.IO}) until initialization is done.
     *
     * @throws IllegalStateException if it failed or did not finish in time; the UI reports the message
     */
    private void awaitReady() {
        try {
            ready.get(READY_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Dropbox failed to start: " + e.getCause().getMessage(), e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("Dropbox is still starting", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the dropbox", e);
        }
        if (destroyed) throw new IllegalStateException("Dropbox service is stopping");
    }

    // ------------------------------------------------------------------------------------------ helpers

    /**
     * Posts the persistent notification and promotes the service to the foreground with type dataSync
     * (manifest declares FOREGROUND_SERVICE + FOREGROUND_SERVICE_DATA_SYNC; Research/R5 §3). Main thread.
     */
    private void startInForeground(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel channel =
                    new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                                            getString(R.string.dropbox_notification_channel_name),
                                            NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel); // no-op if it exists
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text),
                                          ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to start foreground service", e);
        }
    }

    /** Replaces the foreground notification's text with the current role. Main thread. */
    private void updateNotification(String text) {
        try {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(text));
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Could not update notification", e);
        }
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, BundleDropboxActivity.class),
                                                       PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(R.string.dropbox_notification_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.bundledropbox_icon)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
    }

    /** Operator-facing words for a mode (notification and Logs; the Status tab has its own labels). */
    private static String describe(CycleMode mode) {
        return switch (mode) {
            case HOST_STARTING, HOSTING, HOST_DRAINING, HOST_STOPPING -> "serving clients";
            case VISIT_STARTING, VISITING, VISIT_DRAINING, VISIT_STOPPING -> "visiting transports";
            case UNAVAILABLE -> "radio unavailable, retrying";
            case STOPPED -> "stopped";
        };
    }

    private static String shortId(String id) {
        if (id == null) return "(none)";
        return id.length() <= 12 ? id : id.substring(0, 12) + "…";
    }

    /** JUL plus the in-app user log (Logs tab / Local Wifi log), in plain English like the wifi package. */
    private static void logUi(UserLogType type, Level level, String message) {
        logger.log(level, type + ": " + message);
        UserLogRepository.INSTANCE.log(new UserLogRepository.UserLogEntry(type, System.currentTimeMillis(), message,
                                                                          level));
    }

    /** Local binder handed to {@code DropboxServiceManager} (mirrors the transport's service binder). */
    public class LocalBinder extends Binder {
        /** @return the running service instance */
        public DropboxService getService() {
            return DropboxService.this;
        }
    }
}
