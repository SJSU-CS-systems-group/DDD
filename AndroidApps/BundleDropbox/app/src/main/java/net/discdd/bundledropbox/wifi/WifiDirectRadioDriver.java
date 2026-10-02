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

import net.discdd.bundledropbox.R;
import net.discdd.bundledropbox.cycle.RadioCallback;
import net.discdd.bundledropbox.cycle.RadioDriver;
import net.discdd.bundledropbox.cycle.VisitCallback;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * <p>Wiring (integrator): construct in the service, {@link #initialize()}, register
 * {@link DropboxWifiStateBridge} (and/or other listeners), hand the driver to the controller, call
 * {@link #wifiPermissionGranted()} when the UI obtains NEARBY_WIFI_DEVICES, and {@link #shutdown()} in
 * {@code onDestroy} after the controller is stopped.
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
    private boolean initialized;
    private boolean receiverRegistered;
    private boolean discoveryRunning;
    private String lastError;
    private boolean lastAvailable;

    /**
     * @param context              service context (the application context is used internally)
     * @param baseId               stable 16-char id derived from the dropbox TLS key, exactly like the
     *                             transport's transportId (R1 §2.4); used for the group name
     * @param advertisedIdSupplier TXT {@code transportId} to advertise, read at each startHosting
     *                             ({@code baseId-gen} with rotation, 03 Architecture §5)
     * @param recencyBlobSupplier  raw cached {@code GetRecencyBlobResponse} bytes, or null if none
     *                             (e.g. {@code () -> recencyBlobStore.current().orElse(null)})
     * @param endpoint             client-facing server started/stopped with the hosted group (gRPC 7777)
     * @param visitRunner          carrier exchange run after joining a carrier (TransportVisitClient)
     */
    public WifiDirectRadioDriver(Context context,
                                 String baseId,
                                 Supplier<String> advertisedIdSupplier,
                                 Supplier<byte[]> recencyBlobSupplier,
                                 HostingEndpoint endpoint,
                                 VisitRunner visitRunner) {
        this.context = context.getApplicationContext();
        this.link = new P2pLink(this.context, main, this::onChannelLost);
        this.host = new DropboxWifiHost(link,
                                        baseId,
                                        advertisedIdSupplier,
                                        recencyBlobSupplier,
                                        endpoint == null ? HostingEndpoint.NONE : endpoint,
                                        this::publish,
                                        () -> notifyEnvironment(false, "hosted group lost"));
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
                lastError = "Wi-Fi Direct is not supported on this device";
                link.wifi(WARNING, lastError);
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
     * Unregisters the receiver and closes the channel. Call after the controller has stopped (so the
     * group has been removed); in-flight operations are abandoned without callbacks.
     */
    public void shutdown() {
        main.post(() -> {
            if (!initialized) return;
            initialized = false;
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
            notifyEnvironment(isAvailable(), "permission granted");
        });
    }

    /**
     * Re-advertises the TXT record while hosting, e.g. after a visit stored a newer recency blob while the
     * dropbox is (unusually) still hosting. Normally not needed: each startHosting reads both suppliers.
     */
    public void refreshAdvertisement() {
        main.post(host::refreshAdvertisement);
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
                host.start(gen, failureRecorder(cb));
            }
        });
    }

    @Override
    public void stopHosting(long gen, RadioCallback cb) {
        main.post(() -> host.stop(gen, failureRecorder(cb)));
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
                joiner.start(gen, failureRecorder(cb));
            }
        });
    }

    @Override
    public void stopVisit(long gen, RadioCallback cb) {
        main.post(() -> joiner.stop(gen, failureRecorder(cb)));
    }

    // ---------------------------------------------------------------- observable state

    /** @return latest snapshot (thread-safe) */
    public WifiRadioState getState() {
        return state;
    }

    /** Adds a listener and immediately posts the current state to it (on the main thread). */
    public void addStateListener(WifiRadioStateListener listener) {
        listeners.add(listener);
        main.post(() -> listener.onWifiRadioStateChanged(state));
    }

    public void removeStateListener(WifiRadioStateListener listener) {
        listeners.remove(listener);
    }

    /** Sets (or clears with null) the single environment listener. */
    public void setEnvironmentListener(RadioEnvironmentListener listener) {
        environmentListener = listener;
    }

    /** Rebuilds the snapshot from host, joiner and link; main thread only. */
    private void publish() {
        WifiRadioState.Phase phase;
        if (!initialized) {
            phase = WifiRadioState.Phase.OFF;
        } else if (host.isActive()) {
            phase = switch (host.state()) {
                case STARTING -> WifiRadioState.Phase.HOST_STARTING;
                case HOSTING -> WifiRadioState.Phase.HOSTING;
                default -> WifiRadioState.Phase.HOST_STOPPING;
            };
        } else {
            phase = switch (joiner.phase()) {
                case IDLE -> WifiRadioState.Phase.IDLE;
                case STARTING -> WifiRadioState.Phase.VISIT_STARTING;
                case DISCOVERING -> WifiRadioState.Phase.DISCOVERING;
                case CONNECTING -> WifiRadioState.Phase.CONNECTING;
                case EXCHANGING -> WifiRadioState.Phase.EXCHANGING;
                case DISCONNECTING -> WifiRadioState.Phase.DISCONNECTING;
                case STOPPING -> WifiRadioState.Phase.VISIT_STOPPING;
            };
        }
        WifiRadioState.GroupInfo group = host.isActive() ? host.groupInfo() : joiner.groupInfo();
        boolean permissions = link.hasNearbyPermission(); // the only runtime permission P2P needs (P2pLink.precheck)
        state = WifiRadioState.builder()
                .phase(phase)
                .p2pEnabled(link.manager != null && link.p2pConfirmedEnabled())
                .permissionsGranted(permissions)
                .deviceName(link.deviceName())
                .advertisedId(host.currentAdvertisedId())
                .group(group)
                .currentCarrier(joiner.currentCarrier())
                .discoveredCarriers(joiner.isActive() ? joiner.discoveredCarriers() : List.of())
                .lastError(lastError)
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

    /** Wraps a callback so failures land in {@link WifiRadioState#lastError()}. */
    private RadioCallback failureRecorder(RadioCallback cb) {
        return new RadioCallback() {
            @Override
            public void ready(long gen) {
                cb.ready(gen);
            }

            @Override
            public void failed(long gen, String reason) {
                lastError = reason;
                publish();
                cb.failed(gen, reason);
            }
        };
    }

    private VisitCallback failureRecorder(VisitCallback cb) {
        return new VisitCallback() {
            @Override
            public void ready(long gen) {
                cb.ready(gen);
            }

            @Override
            public void failed(long gen, String reason) {
                lastError = reason;
                publish();
                cb.failed(gen, reason);
            }

            @Override
            public void visitFinished(long gen, boolean success) {
                cb.visitFinished(gen, success);
            }
        };
    }

    // ---------------------------------------------------------------- environment

    private boolean isAvailable() {
        return link.manager != null && link.p2pEnabled() && link.hasNearbyPermission();
    }

    private void notifyEnvironment(boolean available, String reason) {
        lastAvailable = available;
        RadioEnvironmentListener l = environmentListener;
        if (l == null) return;
        try {
            l.onRadioAvailabilityChanged(available, reason);
        } catch (RuntimeException e) {
            logger.log(WARNING, "environment listener threw", e);
        }
    }

    private void onChannelLost() {
        lastError = "Wi-Fi Direct channel lost";
        host.onP2pDisabled();
        joiner.onP2pDisabled();
        publish();
        notifyEnvironment(false, lastError);
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
        boolean wasAvailable = lastAvailable;
        link.setP2pState(p2pState);
        boolean enabled = p2pState == WifiP2pManager.WIFI_P2P_STATE_ENABLED;
        if (!enabled) {
            link.wifiRes(WARNING, R.string.Wifi_disabled);
            host.onP2pDisabled();
            joiner.onP2pDisabled();
        }
        publish();
        boolean available = isAvailable();
        // Only edges are interesting: the sticky broadcast repeats the current state on registration.
        if (available != wasAvailable) {
            notifyEnvironment(available, enabled ? "Wi-Fi Direct enabled" : "Wi-Fi Direct disabled");
        }
    }

    private void onDeviceInfo(WifiP2pDevice device) {
        if (device != null) link.setDevice(device.deviceName, device.deviceAddress);
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
                    discoveryRunning = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1) ==
                            WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED;
                    logger.fine("P2P discovery " + (discoveryRunning ? "started" : "stopped"));
                }
                default -> {
                }
            }
        }
    }
}
