package net.discdd.bundleclient.service.wifiDirect;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.net.wifi.p2p.nsd.WifiP2pServiceRequest;
import android.os.HandlerThread;
import androidx.annotation.RequiresPermission;
import androidx.core.app.ActivityCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import net.discdd.bundleclient.R;
import net.discdd.bundleclient.service.BundleClientService;
import net.discdd.bundleclient.service.DDDWifi;
import net.discdd.bundleclient.service.DDDWifiConnection;
import net.discdd.bundleclient.service.DDDWifiDevice;
import net.discdd.bundleclient.service.DDDWifiEventType;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_STATE_DISABLED;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_STATE_ENABLED;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION;
import static java.lang.String.format;
import static java.util.logging.Level.INFO;
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
    private List<DDDWifiDevice> peers = new CopyOnWriteArrayList<>();
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

    private boolean hasPermission() {
        int nearbyWifiPermissionCheck = ActivityCompat.checkSelfPermission(bundleClientService.getApplicationContext(),
                                                                           Manifest.permission.NEARBY_WIFI_DEVICES);
        return nearbyWifiPermissionCheck == PackageManager.PERMISSION_GRANTED;
    }

    private class DDDWifiDirectBroadcastReceiver extends BroadcastReceiver {
        @RequiresPermission(allOf = { Manifest.permission.ACCESS_FINE_LOCATION,
                                      Manifest.permission.NEARBY_WIFI_DEVICES })
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                switch (action) {
                    case WIFI_P2P_STATE_CHANGED_ACTION -> {
                        var wifiState = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                        processStateChange(wifiState);
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
                        logger.info(format("connected to %s", group == null ? "noone" : group.getOwner().deviceName));
                        DDDWifiDirect.this.ownerAddress = conInfo.groupOwnerAddress;
                        // if we got an address, let all the completions waiting for an address know about it.
                        if (conInfo.groupOwnerAddress != null) {
                            logger.info(format("Got connect to %s with address %s",
                                               conGroup.getOwner().deviceName,
                                               conInfo.groupOwnerAddress.getHostAddress()));
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

    private void processStateChange(int wifiState) {
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
            if (hasPermission()) {
                wifiP2pManager.requestDeviceInfo(wifiChannel, (DDDWifiDirect.this::processDeviceInfo));
            }
        }
        eventsLiveData.postValue(DDDWifiEventType.DDDWIFI_STATE_CHANGED);
    }

    private void processDeviceInfo(WifiP2pDevice wifiP2pDevice) {
        if (wifiP2pDevice != null) {
            DDDWifiDirect.this.deviceName = wifiP2pDevice.deviceName;
            DDDWifiDirect.this.status = wifiP2pDevice.status;
        } else {
            this.status = WifiP2pDevice.UNAVAILABLE;
        }
        eventsLiveData.postValue(DDDWifiEventType.DDDWIFI_STATE_CHANGED);
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

    public void initialize() {
        if (wifiChannel != null) {
            logger.severe("Calling initialize on an initialized channel. Ignoring.");
            return;
        }
        this.handlerThread = new HandlerThread("WifiP2PHandlerThread");
        this.handlerThread.start();
        this.wifiChannel = wifiP2pManager.initialize(bundleClientService, handlerThread.getLooper(), () -> logger.warning("Framework lost. Ignoring"));
        if (hasPermission()) {
            registerBroadcastReceiver();
        }

        WifiP2pManager.DnsSdTxtRecordListener txtResponseListener = (type, txtRecord, device) -> {
            logger.log(INFO, format("DnsSdTxtRecord available: %s %s %s", device.deviceName, device.deviceAddress, txtRecord));
            if (txtRecord.get("transportId") != null) {
                discoverPeer(device, txtRecord.get("transportId"));
                checkAwaitingDiscovery();
                eventsLiveData.postValue(DDDWifiEventType.DDDWIFI_PEERS_CHANGED);
            }
        };
        wifiP2pManager.setDnsSdResponseListeners(wifiChannel, null, txtResponseListener);

        WifiP2pServiceRequest serviceRequest = WifiP2pDnsSdServiceRequest.newInstance("ddd", "_ddd._tcp");

        wifiP2pManager.addServiceRequest(wifiChannel, serviceRequest, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                logger.log(INFO, "Added service request for _ddd._tcp");
            }

            @Override
            public void onFailure(int reason) {
                logger.log(SEVERE, "Could not register service request for _ddd._tcp rc = " + reason);
            }
        });
    }

    private void discoverPeer(WifiP2pDevice device, String transportId) {
        boolean peerDiscovered = false;
        DDDWifiDevice newDevice = new DDDWifiDirectDevice(device, transportId);
        DDDWifiDevice peerToReplace = null;

        for (DDDWifiDevice peerDevice: peers) {
            if (peerDevice.getId().equals(newDevice.getId())) {
                peerDiscovered = true;
                if (peerDevice.getDescription().isBlank() && !newDevice.getDescription().isBlank()) {
                    peerToReplace = peerDevice;
                }
            }
        }
        if (!peerDiscovered) {
            peers.add(newDevice);
        } else if (peerToReplace != null) {
            peers.remove(peerToReplace);
            peers.add(newDevice);
        }
    }

    AtomicBoolean isReceiverRegistered = new AtomicBoolean(false);
    private void registerBroadcastReceiver() {
        wifiP2pManager.requestP2pState(wifiChannel, this::processStateChange);
        if (isReceiverRegistered.getAndSet(true)) return;
        bundleClientService.registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED);
    }

    private void unregisterBroadcastReceiver() {
        if (!isReceiverRegistered.getAndSet(false)) return;
        bundleClientService.unregisterReceiver(broadcastReceiver);
    }

    @Override
    public CompletableFuture<Boolean> startDiscovery() {
        var cf = new CompletableFuture<Boolean>();
        try {
            peers.clear();
            wifiP2pManager.discoverServices(wifiChannel, new DDDActionListener(cf));
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
            if (!device.sameAddressAs(con.dev)) {
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
        var con = new DDDWifiDirectConnection(new DDDWifiDirectDevice(groupOwner, bundleClientService.getApplicationContext()
                .getString(R.string.unknown_transportId)), groupOwnerAddress);
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
            logger.log(INFO, format("addAddressWaiter: already connected to %s", dev.getDescription()));
            cf.complete(con);
        }
        logger.log(INFO, format("addAddressWaiter: waiting for connection to %s", dev.getDescription()));
        return cf;
    }

    static class AwaitingDiscovery extends CompletableFuture<Void> {
        final DDDWifiDevice dev;

        AwaitingDiscovery(DDDWifiDevice dev) {
            super();
            this.dev = dev;
        }
    }

    private AtomicReference<AwaitingDiscovery> awaitingDiscovery = new AtomicReference<>(null);

    private void checkAwaitingDiscovery() {
        var waitingForPeer = awaitingDiscovery.get();
        if (waitingForPeer != null && peers.contains(waitingForPeer.dev)) {
            waitingForPeer.complete(null);
            awaitingDiscovery.compareAndSet(waitingForPeer, null);
        }
    }

    private void awaitDiscovery(AwaitingDiscovery cf) {
        if (peers.contains(cf.dev)) {
            cf.complete(null);
        } else {
            awaitingDiscovery.set(cf);
            startDiscovery();
        }
    }

    private void cancelAwaitingDiscovery(AwaitingDiscovery cf) {
        awaitingDiscovery.compareAndSet(cf, null);
    }

    @Override
    public CompletableFuture<DDDWifiConnection> connectTo(DDDWifiDevice dev) {
        var directDev = (DDDWifiDirectDevice) dev;

        // we are going to watch for discovery if we don't already see the device in the list of peers.
        var discoveryCF = new AwaitingDiscovery(dev);
        awaitDiscovery(discoveryCF);
        try {
            // give a bit of time to discover the device we want
            discoveryCF.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // hmmm, we'll let's try anyway...
        }
        var cf = new CompletableFuture<Boolean>();
        var p2pConfig = new WifiP2pConfig();
        p2pConfig.deviceAddress = directDev.wifiP2pDevice.deviceAddress;
        try {
            wifiP2pManager.connect(wifiChannel, p2pConfig, new DDDActionListener(cf));
        } catch (SecurityException e) {
            cf.completeExceptionally(e);
        }
        return cf.thenCompose(connectionStarted -> {
            logger.log(INFO, format("connectTo: connection started to %s: %b", dev.getDescription(), connectionStarted));
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
        unregisterBroadcastReceiver();
        // there is no shutdown for wifiP2pManager
        wifiEnable = false;
        wifiChannel.close();
        wifiChannel = null;
        // shutdown looper
        handlerThread.quit();
        eventsLiveData.postValue(DDDWifiEventType.DDDWIFI_STATE_CHANGED);
    }

    @Override
    public LiveData<DDDWifiEventType> getEventLiveData() {
        return eventsLiveData;
    }

    @Override
    public void wifiPermissionGranted() {
        // reregister the receivers with the new permissions
        unregisterBroadcastReceiver();
        registerBroadcastReceiver();
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
