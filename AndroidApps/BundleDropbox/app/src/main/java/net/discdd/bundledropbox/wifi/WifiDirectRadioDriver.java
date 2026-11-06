package net.discdd.bundledropbox.wifi;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import net.discdd.bundledropbox.R;
import net.discdd.bundledropbox.cycle.RadioCallback;
import net.discdd.bundledropbox.cycle.RadioDriver;
import net.discdd.bundledropbox.cycle.VisitCallback;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

/**
 * The dropbox's Android Wi-Fi Direct implementation of the role cycle's {@link RadioDriver}.
 *
 * <p>It owns the single {@code WifiP2pManager.Channel} and the single {@code BroadcastReceiver}, and
 * composes the two roles of the one P2P interface:
 * <ul>
 *   <li>{@link DropboxWifiHost}: HOST mode, looks like a BundleTransport to clients
 *       ({@link #startHosting}/{@link #stopHosting});</li>
 *   <li>{@link DropboxWifiJoiner}: VISIT mode, looks like a BundleClient to carriers
 *       ({@link #startVisit}/{@link #stopVisit}).</li>
 * </ul>
 * The controller ({@code cycle/RoleCycleController}) never overlaps the two; this class rejects a start
 * of one role while the other is still active, with a clear failure reason.
 *
 * <p>Threading: every public method may be called from any thread and returns immediately; work is
 * posted to a main-thread {@link Handler} (the channel's looper, so all P2P callbacks also arrive there).
 * {@link RadioCallback}s, {@link WifiRadioStateListener}s and the {@link RadioEnvironmentListener} are
 * invoked on the main thread; the controller re-posts to its own executor (RadioCallback contract).
 *
 * <p>Environment events ({@link RadioEnvironmentListener}): availability is edge-detected by
 * {@link AvailabilityEdges} from real observations only (P2P state, permission, channel up/down); a role
 * lost while the radio stays usable (hosted group vanished) is reported separately through
 * {@link RadioEnvironmentListener#onRoleLost} and never touches the edge state (audit findings m3/m10).
 * After the framework drops the channel, the driver re-creates it with {@link WifiTimeouts#CHANNEL_RECOVERY}
 * backoff, so "available" is reported again and the cycle can leave UNAVAILABLE without its 30 s timer.
 *
 * <p>Hosted clients ({@link HostedClientsListener}): the P2P client count of our group, for the role
 * cycle's idle-switch decision (audit finding m5); also readable with {@link #hostedClientCount()}.
 *
 * <p>Wiring (DropboxService): construct, {@link #initialize()}, register {@link DropboxWifiStateBridge},
 * set the environment and hosted-clients listeners, hand the driver to the controller, call
 * {@link #wifiPermissionGranted()} when the UI obtains NEARBY_WIFI_DEVICES, and {@link #shutdown()} after
 * the controller is stopped and {@link #awaitIdle} saw both roles idle.
 *
 * <p>FUTURE: a Wi-Fi Aware or gRPC-over-internet link would be another {@link RadioDriver}; nothing in
 * the controller depends on Wi-Fi Direct.
 */
@SuppressLint("MissingPermission") // permissions are checked before each operation; SecurityException is caught
public final class WifiDirectRadioDriver implements RadioDriver {
    private static final Logger logger = Logger.getLogger(WifiDirectRadioDriver.class.getName());

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final P2pLink link;
    private final DropboxWifiHost host;
    private final DropboxWifiJoiner joiner;
    private final IntentFilter intentFilter = new IntentFilter();
    private final BroadcastReceiver receiver = new P2pReceiver();
    private final List<WifiRadioStateListener> listeners = new CopyOnWriteArrayList<>();

    private volatile WifiRadioState state = WifiRadioState.INITIAL;
    private volatile RadioEnvironmentListener environmentListener;
    private volatile HostedClientsListener hostedClientsListener;
    private volatile int hostedClientCount;
    private final AvailabilityEdges availability = new AvailabilityEdges();
    private final Runnable recoverChannel = this::recoverChannel;
    private int channelRecoveryAttempt;
    private boolean initialized;
    private boolean receiverRegistered;

    /**
     * @param context              service context (the application context is used internally)
     * @param baseId              stable 16-char id derived from the dropbox TLS key, exactly like the
     *                            transport's transportId (R1 §2.4); used for the group name and advertised as
     *                            the TXT {@code transportId} (09 Own Recency Blob Design)
     * @param recencyBlobSupplier the dropbox's own raw {@code GetRecencyBlobResponse} bytes, or null if none
     *                            (e.g. {@code () -> recencyBlobStore.current().orElse(null)})
     * @param endpoint            client-server hooks around hosting: ensure the always-on gRPC server (7777)
     *                            is up before advertising, end client sessions on stopHosting
     * @param visitRunner         carrier exchange run after joining a carrier (TransportVisitClient)
     */
    public WifiDirectRadioDriver(Context context,
                                 String baseId,
                                 Supplier<byte[]> recencyBlobSupplier,
                                 HostingEndpoint endpoint,
                                 VisitRunner visitRunner) {
        this.context = context.getApplicationContext();
        this.link = new P2pLink(this.context, main, this::onChannelLost);
        this.host = new DropboxWifiHost(link,
                                        baseId,
                                        recencyBlobSupplier,
                                        endpoint == null ? HostingEndpoint.NONE : endpoint,
                                        this::publish,
                                        this::reportRoleLost,
                                        this::onHostedClientCount);
        this.joiner = new DropboxWifiJoiner(link, visitRunner, this::publish);
        intentFilter.addAction(WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        intentFilter.addAction(WIFI_P2P_DISCOVERY_CHANGED_ACTION);
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Creates the channel and registers the receiver. Does <b>not</b> create a group or start discovery:
     * only the controller decides the role (unlike DDDWifiServer.initialize, which creates a group).
     */
    public void initialize() {
        main.post(() -> {
            if (initialized) return;
            initialized = true;
            link.wifiRes(INFO, R.string.initializing_wifidirectmanager);
            if (link.manager == null) {
                link.wifi(WARNING, "Wi-Fi Direct is not supported on this device");
            } else if (!link.ensureChannel()) {
                link.wifiRes(WARNING, R.string.could_not_initialize_wifi_direct_s, "channel is null");
            } else {
                registerReceiver();
                requestStates();
                if (link.hasNearbyPermission()) link.wifiRes(INFO, R.string.wifi_direct_initialized);
                else link.wifiRes(WARNING, R.string.no_permission_for_wi_fi_direct);
            }
            publish();
        });
    }

    /**
     * Blocks the calling thread until neither role is active (any stopHosting/stopVisit in flight has
     * finished, so the group is gone or its removal failed), or {@code timeoutMs} passes. Service teardown
     * calls it before {@link #shutdown()}, bounded by {@link WifiTimeouts#STOP_WORST_MS}, so closing the
     * channel cannot cut a stop short (audit WD-2). Each check runs on the main thread after everything
     * already posted there, such as a stop the controller just issued. Never call it on the main thread.
     *
     * @return true if both roles were seen idle in time
     */
    public boolean awaitIdle(long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (true) {
            CompletableFuture<Boolean> idle = new CompletableFuture<>();
            main.post(() -> idle.complete(!host.isActive() && !joiner.isActive()));
            try {
                long left = Math.max(1L, deadline - SystemClock.elapsedRealtime());
                if (idle.get(left, TimeUnit.MILLISECONDS)) return true;
            } catch (TimeoutException | ExecutionException e) {
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (SystemClock.elapsedRealtime() >= deadline) return false;
            SystemClock.sleep(100);
        }
    }

    /**
     * Unregisters the receiver and closes the channel. Call after the controller has stopped and
     * {@link #awaitIdle} returned (so the group has been removed); in-flight operations are abandoned without
     * callbacks.
     */
    public void shutdown() {
        main.post(() -> {
            if (!initialized) return;
            initialized = false;
            main.removeCallbacks(recoverChannel);
            unregisterReceiver();
            link.close();
            state = WifiRadioState.INITIAL;
            notifyListeners();
        });
    }

    /**
     * The UI obtained NEARBY_WIFI_DEVICES. Re-registers the receiver so
     * P2P broadcasts (which need the permission) start arriving, as the transport and client do.
     */
    public void wifiPermissionGranted() {
        main.post(() -> {
            if (!initialized) return;
            link.ensureChannel();
            unregisterReceiver();
            registerReceiver();
            requestStates();
            publish();
            reportObservation("permission granted", false);
        });
    }

    // ---------------------------------------------------------------- RadioDriver

    @Override
    public void startHosting(long gen, RadioCallback cb) {
        main.post(() -> {
            if (!initialized) {
                cb.failed(gen, "Wi-Fi Direct driver not initialized");
            } else if (joiner.isActive()) {
                cb.failed(gen, "cannot host while a visit is active (stopVisit first)");
            } else {
                host.start(gen, cb);
            }
        });
    }

    @Override
    public void stopHosting(long gen, RadioCallback cb) {
        main.post(() -> host.stop(gen, cb));
    }

    @Override
    public void withdrawHostAdvertisement() {
        main.post(host::withdrawAdvertisement);
    }

    @Override
    public void startVisit(long gen, VisitCallback cb) {
        main.post(() -> {
            if (!initialized) {
                cb.failed(gen, "Wi-Fi Direct driver not initialized");
            } else if (host.isActive()) {
                cb.failed(gen, "cannot visit while hosting (stopHosting first)");
            } else {
                joiner.start(gen, cb);
            }
        });
    }

    @Override
    public void stopVisit(long gen, RadioCallback cb) {
        main.post(() -> joiner.stop(gen, cb));
    }

    // ---------------------------------------------------------------- observable state

    /** Adds a listener and immediately posts the current state to it (on the main thread). */
    public void addStateListener(WifiRadioStateListener listener) {
        listeners.add(listener);
        main.post(() -> listener.onWifiRadioStateChanged(state));
    }

    /** Sets (or clears with null) the single environment listener. */
    public void setEnvironmentListener(RadioEnvironmentListener listener) {
        environmentListener = listener;
    }

    /**
     * Sets (or clears with null) the single hosted-clients listener. It is called on the main thread
     * whenever the P2P client count of our hosted group changes (edge-triggered, including the drop to 0);
     * the service forwards it to {@code RoleCycleController.hostedClientsChanged()} so the joined-client
     * grace ends without waiting for its next poll of {@link #hostedClientCount()}.
     */
    public void setHostedClientsListener(HostedClientsListener listener) {
        hostedClientsListener = listener;
    }

    /**
     * RadioDriver hook for the joined-client grace (audit m5): the cached count from the last
     * {@code WifiP2pGroup.getClientList()} the host saw. Cheap and thread-safe (volatile read), never calls
     * into WifiP2pManager.
     *
     * @return P2P clients currently in our hosted group, 0 when not hosting
     */
    @Override
    public int hostedClientCount() {
        return hostedClientCount;
    }

    /** Rebuilds the snapshot from host, joiner and link; main thread only. */
    private void publish() {
        WifiRadioState.GroupInfo group = host.isActive() ? host.groupInfo() : joiner.groupInfo();
        state = WifiRadioState.builder()
                .p2pEnabled(link.manager != null && link.p2pConfirmedEnabled())
                .deviceName(link.deviceName())
                .group(group)
                .currentCarrier(joiner.currentCarrier())
                .discoveredCarriers(joiner.isActive() ? joiner.discoveredCarriers() : List.of())
                .build();
        notifyListeners();
    }

    private void notifyListeners() {
        WifiRadioState s = state;
        for (WifiRadioStateListener l : listeners) {
            try {
                l.onWifiRadioStateChanged(s);
            } catch (RuntimeException e) {
                logger.log(WARNING, "Wi-Fi state listener threw", e);
            }
        }
    }

    // ---------------------------------------------------------------- environment

    /** @return true when the radio is usable: P2P supported, channel up, P2P not disabled, NEARBY granted */
    private boolean isAvailable() {
        return link.manager != null && link.channel() != null && link.p2pEnabled() && link.hasNearbyPermission();
    }

    /**
     * Observes the real environment and tells the listener what {@link AvailabilityEdges#report} decides: an
     * availability edge, or, when there is no edge but an established role just ended ({@code lostRole}), a
     * role loss (audit m3/m10; one decision for every path, CL-7).
     *
     * @param lostRole true if {@link #loseRoles} just ended an established role
     */
    private void reportObservation(String reason, boolean lostRole) {
        boolean available = isAvailable();
        switch (availability.report(available, lostRole)) {
            case EDGE -> {
                RadioEnvironmentListener l = environmentListener;
                if (l == null) return;
                try {
                    l.onRadioAvailabilityChanged(available, reason);
                } catch (RuntimeException e) {
                    logger.log(WARNING, "environment listener threw", e);
                }
            }
            case ROLE_LOST -> reportRoleLost(reason);
            case NONE -> {
            }
        }
    }

    /** A role was lost while the radio stays usable; deliberately not an availability observation. */
    private void reportRoleLost(String reason) {
        RadioEnvironmentListener l = environmentListener;
        if (l == null) return;
        try {
            l.onRoleLost(reason);
        } catch (RuntimeException e) {
            logger.log(WARNING, "environment listener threw", e);
        }
    }

    /** Host callback: client count of our group changed (main thread). */
    private void onHostedClientCount(int count) {
        hostedClientCount = count;
        HostedClientsListener l = hostedClientsListener;
        if (l == null) return;
        try {
            l.onHostedClientCountChanged(count);
        } catch (RuntimeException e) {
            logger.log(WARNING, "hosted-clients listener threw", e);
        }
    }

    /**
     * Wi-Fi Direct went away under both roles (disabled or channel lost): fail in-flight ops now and end
     * an established role.
     *
     * @return true if an established HOSTING or VISIT role was ended
     */
    private boolean loseRoles(String reason) {
        boolean hostLost = host.onRadioLost(reason);
        boolean visitLost = joiner.onRadioLost(reason);
        return hostLost || visitLost;
    }

    private void onChannelLost() {
        String reason = "Wi-Fi Direct channel lost";
        boolean lostRole = loseRoles(reason);
        publish();
        // The channel is part of availability, so this is normally a real "unavailable" edge (which the
        // service maps to roleLost); role loss is reported explicitly only if the edge state already said so.
        reportObservation(reason, lostRole);
        channelRecoveryAttempt = 0;
        scheduleChannelRecovery();
    }

    private void scheduleChannelRecovery() {
        main.removeCallbacks(recoverChannel);
        long delay = WifiTimeouts.CHANNEL_RECOVERY.delayBeforeRetry(channelRecoveryAttempt++);
        if (delay >= 0) main.postDelayed(recoverChannel, delay);
    }

    /**
     * Re-creates a channel the framework dropped (or picks up one a radio op already re-created) and asks
     * for the P2P state, whose answer reports the "available" edge (audit finding m10: before this, a
     * lazily re-created channel never produced one, so UNAVAILABLE waited out its 30 s timer).
     */
    private void recoverChannel() {
        if (!initialized) return;
        if (link.ensureChannel()) {
            link.wifi(INFO, "Wi-Fi Direct channel re-initialized");
            requestStates();
            publish();
        } else {
            scheduleChannelRecovery();
        }
    }

    private void requestStates() {
        WifiP2pManager.Channel ch = link.channel();
        if (link.manager == null || ch == null) return;
        link.manager.requestP2pState(ch, this::onP2pState);
        if (link.hasNearbyPermission()) {
            try {
                link.manager.requestDeviceInfo(ch, this::onDeviceInfo);
            } catch (SecurityException e) {
                logger.warning("requestDeviceInfo: " + e.getMessage());
            }
        }
    }

    private void onP2pState(int p2pState) {
        link.setP2pState(p2pState);
        boolean enabled = p2pState == WifiP2pManager.WIFI_P2P_STATE_ENABLED;
        boolean lostRole = false;
        if (!enabled) {
            link.wifiRes(WARNING, R.string.Wifi_disabled);
            lostRole = loseRoles("Wi-Fi Direct was disabled");
        }
        publish();
        // Only edges are reported: the sticky broadcast repeats the current state on registration. If a role
        // was running although the edge state already said "unavailable", still tell the cycle it is gone.
        reportObservation(enabled ? "Wi-Fi Direct enabled" : "Wi-Fi Direct disabled", lostRole);
    }

    private void onDeviceInfo(WifiP2pDevice device) {
        if (device != null) link.setDevice(device.deviceName);
        publish();
    }

    private void registerReceiver() {
        if (receiverRegistered) return;
        context.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    private void unregisterReceiver() {
        if (!receiverRegistered) return;
        receiverRegistered = false;
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException e) {
            logger.fine("receiver was not registered");
        }
    }

    /**
     * Single receiver for both roles; dispatches to host and joiner. Runs on the main thread. Deliberately
     * does not create a group when P2P becomes enabled (DDDWifiServer does): the controller decides.
     */
    private final class P2pReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (action == null || !initialized) return;
            switch (action) {
                case WIFI_P2P_STATE_CHANGED_ACTION ->
                        onP2pState(intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1));
                case WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    WifiP2pInfo info = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo.class);
                    WifiP2pGroup group = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP, WifiP2pGroup.class);
                    host.onConnectionChanged(info, group);
                    joiner.onConnectionChanged(info, group);
                    publish();
                }
                case WIFI_P2P_THIS_DEVICE_CHANGED_ACTION ->
                        onDeviceInfo(intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice.class));
                case WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                    // Debug aid only: nothing depends on it (the joiner re-issues discovery on its own timer).
                    boolean started = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1) ==
                            WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED;
                    logger.fine("P2P discovery " + (started ? "started" : "stopped"));
                }
                default -> {
                }
            }
        }
    }
}
