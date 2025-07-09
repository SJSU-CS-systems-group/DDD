package net.discdd.bundleclient.service.wifiDirect;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.HandlerThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import net.discdd.bundleclient.service.BundleClientService;
import net.discdd.bundleclient.service.DDDWifi;
import net.discdd.bundleclient.service.DDDWifiConnection;
import net.discdd.bundleclient.service.DDDWifiDevice;
import net.discdd.bundleclient.service.DDDWifiEventType;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static android.net.wifi.p2p.WifiP2pManager.EXTRA_P2P_DEVICE_LIST;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_STATE_DISABLED;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_STATE_ENABLED;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION;
import static java.lang.String.format;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

public class DDDWifiDirect implements DDDWifi {
    private static final Logger logger = Logger.getLogger(DDDWifiDirect.class.getName());
    final private BundleClientService bundleClientService;
    private final IntentFilter intentFilter;
    private final WifiP2pManager wifiP2pManager;
    private HandlerThread handlerThread;
    private WifiP2pManager.Channel wifiChannel;
    private boolean discoveryActive = false;
    private WifiP2pGroup group;
    private InetAddress ownerAddress;
    private List<DDDWifiDevice> peers = new ArrayList<>();
    final private DDDWifiDirectBroadcastReceiver broadcastReceiver = new DDDWifiDirectBroadcastReceiver();
    private final MutableLiveData<DDDWifiEventType> eventsLiveData = new MutableLiveData<>();
    private String deviceName;
    final private static String[] STATUS_STRINGS = {"CONNECTED",
                                                    "INVITED",
                                                    "FAILED",
                                                    "AVAILABLE",
                                                    "UNAVAILABLE"};
    private int status = WifiP2pDevice.UNAVAILABLE;
    private boolean wifiEnable;

    private class DDDWifiDirectBroadcastReceiver extends BroadcastReceiver {
        @SuppressLint("MissingPermission")
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                switch (action) {
                    case WIFI_P2P_STATE_CHANGED_ACTION -> {
                        var wifiState = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                        switch(wifiState) {
                            case WIFI_P2P_STATE_ENABLED -> {
                                if (!wifiEnable) {
                                    // we are switching from disabled to enabled, so discover peers
                                    startDiscovery();
                                }
                                wifiEnable = true;
                            }
                            case WIFI_P2P_STATE_DISABLED -> wifiEnable = false;
                            default -> logger.log(WARNING, "unknown p2p state %d", wifiState);
                        }
                        if (wifiEnable) {
                            wifiP2pManager.requestDeviceInfo(wifiChannel, (DDDWifiDirect.this::processDeviceInfo));
                        }
                        eventsLiveData.postValue(DDDWifiEventType.DDDWIFI_STATE_CHANGED);
                    }
                    case WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        // Broadcast intent action indicating that the available peer list has changed.
                        // This can be sent as a result of peers being found, lost or updated.
                        try {
                            var newPeerList = intent.getParcelableExtra(EXTRA_P2P_DEVICE_LIST, WifiP2pDeviceList.class);
                            if (newPeerList != null) {
                                peers = newPeerList.getDeviceList()
                                        .stream()
                                        .filter(d -> d.deviceName.startsWith("ddd_"))
                                        .map(DDDWifiDirectDevice::new)
                                        .collect(Collectors.toList());
                                var waitingForPeer = awaitingDiscovery.get();
                                if (waitingForPeer != null && peers.contains(waitingForPeer.dev)) {
                                    waitingForPeer.complete(null);
                                    awaitingDiscovery.compareAndSet(waitingForPeer, null);
                                }
                            }
                            eventsLiveData.postValue(DDDWifiEventType.DDDWIFI_PEERS_CHANGED);
                        } catch (SecurityException e) {
                            logger.log(SEVERE, "SecurityException in requestPeers", e);
                        }

                    }
                    case WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        //         Broadcast intent action indicating that the state of Wi-Fi p2p
                        //         connectivity has changed.
                        //         EXTRA_WIFI_P2P_INFO provides the p2p connection info in the form of a
                        //         WifiP2pInfo object.
                        //         EXTRA_WIFI_P2P_GROUP provides the details of the group and
                        //         may contain a null
                        var conInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo.class);
                        var conGroup =
                                intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP, WifiP2pGroup.class);
                        DDDWifiDirect.this.group = conGroup;
                        DDDWifiDirect.this.ownerAddress = conInfo.groupOwnerAddress;
                        // if we got an address, let all the completions waiting for an address know about it.
                        if (conInfo.groupOwnerAddress != null) {
                            completeAddressWaiters(conGroup.getOwner(), conInfo.groupOwnerAddress);
                        }
                        eventsLiveData.postValue(DDDWifiEventType.DDDWIFI_STATE_CHANGED);
                    }
                    case WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        WifiP2pDevice wifiP2pDevice = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                                                                    WifiP2pDevice.class);
                        processDeviceInfo(wifiP2pDevice);
                    }
                    case WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                        int discoveryState = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1);
                        discoveryActive = discoveryState == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED;
                        eventsLiveData.postValue(DDDWifiEventType.DDDWIFI_DISCOVERY_CHANGED);
                    }
                }
            }
        }
    }

    private void processDeviceInfo(WifiP2pDevice wifiP2pDevice) {
        var infoChanged = false;
        if (wifiP2pDevice != null) {
            if (!wifiP2pDevice.deviceName.equals(deviceName)) {
                DDDWifiDirect.this.deviceName = wifiP2pDevice.deviceName;
                infoChanged = true;
            }
            if (wifiP2pDevice.status != status) {
                DDDWifiDirect.this.status = wifiP2pDevice.status;
                infoChanged = true;
            }
        } else {
            if (this.status != WifiP2pDevice.UNAVAILABLE) {
                infoChanged = true;
                this.status = WifiP2pDevice.UNAVAILABLE;
            }
        }
        if (infoChanged) {
            eventsLiveData.postValue(DDDWifiEventType.DDDWIFI_STATE_CHANGED);
        }
    }

    public DDDWifiDirect(BundleClientService bundleClientService) {
        this.bundleClientService = bundleClientService;
        var intentFilter = new IntentFilter();
        intentFilter.addAction(WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        intentFilter.addAction(WIFI_P2P_DISCOVERY_CHANGED_ACTION);
        this.intentFilter = intentFilter;
        this.wifiP2pManager = (WifiP2pManager) bundleClientService.getSystemService(Context.WIFI_P2P_SERVICE);
    }

    @SuppressLint("MissingPermission")
    public void initialize() {
        if (wifiChannel != null) {
            logger.severe("Calling initialize on an initialized channel. Ignoring.");
            return;
        }
        this.handlerThread = new HandlerThread("WifiP2PHandlerThread");
        this.handlerThread.start();
        this.wifiChannel = wifiP2pManager.initialize(bundleClientService, handlerThread.getLooper(), () -> logger.warning("Framework lost. Ignoring"));
        bundleClientService.registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public CompletableFuture<Boolean> startDiscovery() {
        var cf = new CompletableFuture<Boolean>();
        try {
            wifiP2pManager.discoverPeers(wifiChannel, new DDDActionListener(cf));
        } catch (SecurityException e) {
            cf.completeExceptionally(e);
        }
        return cf;
    }

    @Override
    public boolean isDiscoveryActive() {
        return discoveryActive;
    }

    @Override
    public String getStateDescription() {
        var statusString = status >= 0 && status < STATUS_STRINGS.length ? STATUS_STRINGS[status] : "UNKNOWN";
        if (wifiP2pManager == null) {
            return "🚫: " + statusString;
        }
        if (wifiChannel == null) {
            return "📶: " + statusString;
        }

        var statusBuilder = new StringBuilder();
        statusBuilder.append("🛜: ");
        statusBuilder.append(statusString);
        if (group == null) {
            return statusBuilder.toString();
        }

        statusBuilder.append(" ");
        statusBuilder.append(group.getOwner().deviceName);
        if (ownerAddress != null) {
            statusBuilder.append(" ");
            statusBuilder.append(ownerAddress.getHostAddress());
            return statusBuilder.toString();
        }

        return statusBuilder.toString();
    }

    @Override
    public boolean isDddWifiEnabled() {
        return wifiEnable;
    }

    static private class DDDWifiDirectCompletableConnection extends CompletableFuture<DDDWifiConnection> {
        private final DDDWifiDirectDevice device;

        DDDWifiDirectCompletableConnection(DDDWifiDirectDevice device) {
            this.device = device;
        }

        /**
         * completes the waiting futures with the given connection.
         * if the connection doesn't match the device the completable future was created for,
         * it will complete exceptionally with a DDDWifiConnectionException.
         */
        void completeWithConnection(DDDWifiDirectConnection con) throws DDDWifiException.DDDWifiConnectionException {
            if (!device.equals(con.dev)) {
                completeExceptionally(new DDDWifiException.DDDWifiConnectionException(format("connected to %s rather than %s",
                                                                             con.dev.wifiP2pDevice.deviceName,
                                                                             device.wifiP2pDevice.deviceName), null));
            } else {
                complete(con);
            }
        }
    }

    static private class ConnectionWaiter {
        private DDDWifiDirectConnection connection;
        CompletableFuture<Void> completableFuture;
    }
    final private List<DDDWifiDirectCompletableConnection> addressFutures = new ArrayList<>();

    private void completeAddressWaiters(WifiP2pDevice groupOwner, InetAddress groupOwnerAddress) {
        ArrayList<DDDWifiDirectCompletableConnection> toComplete;
        synchronized (addressFutures) {
            toComplete = new ArrayList<>(addressFutures);
            addressFutures.clear();
        }
        var con = new DDDWifiDirectConnection(new DDDWifiDirectDevice(groupOwner), groupOwnerAddress);
        toComplete.forEach(cf -> cf.completeWithConnection(con));
    }

    private boolean fireableConnectionWaiter(ConnectionWaiter cw, WifiP2pDevice groupOwner) {
        var groupOwnerToDisconnect = cw.connection.dev.wifiP2pDevice;
        return !groupOwnerToDisconnect.equals(groupOwner);
    }

    private CompletableFuture<DDDWifiConnection> addAddressWaiter(DDDWifiDirectDevice dev) {
        var cf = new DDDWifiDirectCompletableConnection(dev);
        DDDWifiConnection con = null;
        synchronized (addressFutures) {
            if (group != null && group.getOwner().deviceAddress.equals(dev.getWifiAddress()) && ownerAddress != null) {
                con = new DDDWifiDirectConnection(dev, ownerAddress);
            } else {
                addressFutures.add(cf);
            }
        }
        // if we already have the connection we need, complete outside of the synchronized block
        if (con != null) {
            cf.complete(con);
        }
        return cf;
    }

    static class AwaitingDiscovery extends CompletableFuture<Void> {
        final DDDWifiDevice dev;

        AwaitingDiscovery(DDDWifiDevice dev) {
            super();
            this.dev = dev;
        }
    }
    AtomicReference<AwaitingDiscovery> awaitingDiscovery = new AtomicReference<>(null);

    @Override
    public CompletableFuture<DDDWifiConnection> connectTo(DDDWifiDevice dev) {
        var directDev = (DDDWifiDirectDevice) dev;

        // we are going to watch for discovery if we don't already see the device in the list of peers.
        var discoveryCF = new AwaitingDiscovery(dev);
        awaitingDiscovery.set(discoveryCF);
        if (!peers.contains(dev)) {
            // we should start discovery to see if the device is around
            startDiscovery();
            try {
                // give a bit of time to discover the device we want
                discoveryCF.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                // hmmm, we'll let's try anyway...
            }
        }
        awaitingDiscovery.compareAndExchange(discoveryCF, null);

        var cf = new CompletableFuture<Boolean>();
        var p2pConfig = new WifiP2pConfig();
        p2pConfig.deviceAddress = directDev.wifiP2pDevice.deviceAddress;
        try {
            wifiP2pManager.connect(wifiChannel, p2pConfig, new DDDActionListener(cf));
        } catch (SecurityException e) {
            cf.completeExceptionally(e);
        }
        return cf.thenCompose(connectionStarted -> {
            if (!connectionStarted) throw new DDDWifiException.DDDWifiConnectionException("Failed to start connection to " + dev.getDescription(), null);
            return addAddressWaiter(directDev);
        });
    }

    @Override
    synchronized public List<DDDWifiDevice> listDevices() {
        return peers;
    }

    @Override
    public CompletableFuture<Void> disconnectFrom(DDDWifiConnection con) {
        var cf = new CompletableFuture<Boolean>();
        try {
            // we can only be connected to one device at a time, so we can just remove the group
            wifiP2pManager.removeGroup(wifiChannel, new DDDActionListener(cf));
        } catch (SecurityException e) {
            cf.completeExceptionally(e);
        }
        return cf.thenAccept(d -> {});
    }

    public void shutdown() {
        if (wifiChannel == null) {
            logger.severe("calling shutdown on an uninitialized channel. ignoring");
            return;
        }
        // unregister the broadcast receiver
        bundleClientService.unregisterReceiver(broadcastReceiver);
        // shutdown looper
        handlerThread.quit();
        // there is no shutdown for wifiP2pManager
        wifiEnable = false;
        wifiChannel = null;
        eventsLiveData.postValue(DDDWifiEventType.DDDWIFI_STATE_CHANGED);
        wifiChannel.close();
    }

    @Override
    public LiveData<DDDWifiEventType> getEventLiveData() {
        return eventsLiveData;
    }
}

class DDDActionListener implements WifiP2pManager.ActionListener {
    private final CompletableFuture<Boolean> cf;

    public DDDActionListener(CompletableFuture<Boolean> cf) {
        this.cf = cf;
    }

    @Override
    public void onSuccess() {
        cf.complete(Boolean.TRUE);
    }

    @Override
    public void onFailure(int reason) {
        cf.complete(Boolean.FALSE);
    }}
