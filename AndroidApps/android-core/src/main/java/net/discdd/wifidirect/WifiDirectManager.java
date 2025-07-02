package net.discdd.wifidirect;

import static android.net.wifi.p2p.WifiP2pManager.EXTRA_P2P_DEVICE_LIST;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION;
import static net.discdd.wifidirect.WifiDirectManager.WifiDirectEventType.WIFI_DIRECT_MANAGER_DEVICE_INFO_CHANGED;
import static net.discdd.wifidirect.WifiDirectManager.WifiDirectEventType.WIFI_DIRECT_MANAGER_INITIALIZED;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;

import androidx.annotation.RequiresPermission;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Main WifiDirect class
 * Contains wrapper methods around common WifiDirect tasks
 */
public class WifiDirectManager {
    private static final Logger logger = Logger.getLogger(WifiDirectManager.class.getName());

    private final IntentFilter intentFilter = new IntentFilter();
    private final Context context;
    private final List<WifiDirectStateListener> listeners = new ArrayList<>();
    private final boolean isOwner;
    private String deviceName;
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private WifiDirectBroadcastReceiver receiver;
    private WifiDirectStatus status = WifiDirectStatus.UNDEFINED;
    private HashSet<WifiP2pDevice> discoveredPeers = new HashSet<>();
    private WifiP2pGroup groupInfo;
    private InetAddress groupOwnerAddress;
    private boolean wifiDirectEnabled;
    private boolean discoveryActive;

    public WifiDirectManager(Context context, WifiDirectStateListener listener, boolean isOwner) {
        this.context = context;
        listeners.add(listener);
        this.isOwner = isOwner;
        intentFilter.addAction(WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        intentFilter.addAction(WIFI_P2P_DISCOVERY_CHANGED_ACTION);
    }

    @RequiresPermission(allOf = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES })
    public void initialize() {
        this.manager = (WifiP2pManager) this.context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            logger.log(INFO, "Cannot get Wi-Fi system service");
        } else {
            this.channel = this.manager.initialize(this.context,
                                                   this.context.getMainLooper(),
                                                   () -> notifyActionToListeners(WIFI_DIRECT_MANAGER_INITIALIZED,
                                                                                 "Channel disconnected"));
            if (channel == null) {
                logger.log(WARNING, "Cannot initialize Wi-Fi Direct");
            }

        }

        this.receiver = new WifiDirectBroadcastReceiver();
        registerWifiIntentReceiver();
        manager.requestDeviceInfo(channel, this::processDeviceInfo);
    }

    // package protected so that the broadcast receiver can call it
    @RequiresPermission(allOf = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES })
    void processDeviceInfo(WifiP2pDevice wifiP2pDevice) {
        if (wifiP2pDevice != null) {
            var infoChanged = false;
            if (!wifiP2pDevice.deviceName.equals(deviceName)) {
                this.deviceName = wifiP2pDevice.deviceName;
                // device name changed, so redo the group
                if (isOwner) {
                    // device name changed, so redo the group
                    if (wifiP2pDevice.status == WifiP2pDevice.CONNECTED) removeGroup();
                    if (deviceName.startsWith("ddd_")) createGroup();
                    infoChanged = true;
                }
            }
            var newStatus = switch (wifiP2pDevice.status) {
                case WifiP2pDevice.CONNECTED -> WifiDirectStatus.CONNECTED;
                case WifiP2pDevice.INVITED -> WifiDirectStatus.INVITED;
                case WifiP2pDevice.FAILED -> WifiDirectStatus.FAILED;
                case WifiP2pDevice.AVAILABLE -> WifiDirectStatus.AVAILABLE;
                case WifiP2pDevice.UNAVAILABLE -> WifiDirectStatus.UNAVAILABLE;
                default -> WifiDirectStatus.UNDEFINED;
            };
            if (!status.equals(newStatus)) {
                status = newStatus;
                infoChanged = true;
            }
            if (infoChanged) {
                notifyActionToListeners(WIFI_DIRECT_MANAGER_DEVICE_INFO_CHANGED);
            }
        }
    }

    public String getDeviceName() {
        return deviceName;
    }

    public WifiDirectStatus getStatus() {
        return status;
    }

    public boolean isDiscoveryActive() {
        return discoveryActive;
    }

    /**
     * Discovers WifiDirect peers for this device.
     *
     * @return Completable Future true if discovery is successful false if not
     */

    @SuppressLint("MissingPermission")
    public CompletableFuture<OptionalInt> discoverPeers() {
        var completableActionListener = new CompletableActionListener();
        this.manager.discoverPeers(this.channel, completableActionListener);
        return completableActionListener;
    }

    @RequiresPermission(allOf = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES })
    public CompletableFuture<WifiP2pGroup> createGroup() {
        var completableFuture = new CompletableFuture<WifiP2pGroup>();
        this.manager.createGroup(this.channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                logger.log(INFO, "Wifi direct group created");
                requestGroupInfo().thenAccept(completableFuture::complete);
            }

            @Override
            public void onFailure(int reasonCode) {
                logger.log(SEVERE, "Wifi direct group creation failed with reason code: " + reasonCode);
                WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                if (wifiManager.isWifiEnabled()) {
                    logger.log(INFO, "Wi-Fi is enabled");
                } else {
                    logger.log(INFO, "Wi-Fi is NOT enabled");
                }
                requestGroupInfo().thenAccept(completableFuture::complete);
            }
        });
        return completableFuture;
    }

    /**
     * Leave the current WifiDirect Group this device is connected to
     *
     * @return Future containing true if successful false if not
     */
    public CompletableFuture<OptionalInt> removeGroup() {
        var cal = new CompletableActionListener();
        this.manager.removeGroup(this.channel, cal);
        return cal.thenApply(rc -> {
            if (rc.isPresent()) {
                logger.log(WARNING,
                           "Failed to remove a group with reasonCode: " + rc.getAsInt() +
                                   " Note: this could mean device was never part of a group");
            }
            return rc;
        });
    }

    /**
     * Get the GroupInfo of current WifiDirect devices connected to this group.
     *
     * @return WifiP2PGroup object containing list of connected devices.
     */
    @SuppressLint("MissingPermission")
    public CompletableFuture<WifiP2pGroup> requestGroupInfo() {
        CompletableFuture<WifiP2pGroup> cFuture = new CompletableFuture<>();
        this.manager.requestGroupInfo(channel, gi -> {
            this.groupInfo = gi;
            this.manager.requestConnectionInfo(channel, ci -> cFuture.complete(gi));
        });
        return cFuture;
    }

    @RequiresPermission(allOf = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES })
    public CompletableFuture<WifiP2pGroup> connect(WifiP2pDevice device) {
        CompletableFuture<WifiP2pGroup> completableFuture = new CompletableFuture<>();
        var config = new WifiP2pConfig();
        config.wps.setup = WpsInfo.PBC;
        config.deviceAddress = device.deviceAddress;
        this.manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                requestGroupInfo().thenAccept(completableFuture::complete);
            }

            @Override
            public void onFailure(int reasonCode) {
                requestGroupInfo().thenAccept(completableFuture::complete);
            }
        });
        return completableFuture;
    }

    /**
     * Get the App context associated with this WifiDirectManager
     *
     * @return the app context
     */
    public Context getContext() {return this.context;}

    void notifyActionToListeners(WifiDirectEventType type) {
        notifyActionToListeners(type, null);
    }

    void notifyActionToListeners(WifiDirectEventType action, String message) {
        var event = new WifiDirectEvent(action, message);
        for (WifiDirectStateListener listener : listeners) {
            listener.onReceiveAction(event);
        }
    }

    public HashSet<WifiP2pDevice> getPeerList() {return discoveredPeers;}

    public CompletableFuture<Boolean> disconnect() {
        CompletableFuture<Boolean> completableFuture = new CompletableFuture<>();
        manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                completableFuture.complete(true);
            }

            @Override
            public void onFailure(int reason) {
                completableFuture.complete(false);
                logger.warning("Failed to disconnect from with reason " + reason);
            }
        });
        return completableFuture;
    }

    public void unregisterWifiIntentReceiver() {
        getContext().unregisterReceiver(receiver);
    }

    public void registerWifiIntentReceiver() {
        getContext().registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED);
    }

    @RequiresPermission(allOf = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES })
    public CompletableFuture<WifiP2pDevice> requestDeviceInfo() {
        var completableFuture = new CompletableFuture<WifiP2pDevice>();
        manager.requestDeviceInfo(channel, di -> {
            processDeviceInfo(di);
            completableFuture.complete(di);
        });
        return completableFuture;
    }

    public CompletableFuture<OptionalInt> shutdown() {
        var cal = new CompletableActionListener();
        manager.removeGroup(channel, null);
        manager.cancelConnect(channel, null);
        manager.stopPeerDiscovery(channel, null);
        manager.stopListening(channel, cal);
        unregisterWifiIntentReceiver();
        return cal;
    }

    public WifiP2pGroup getGroupInfo() {
        return groupInfo;
    }

    public InetAddress getGroupOwnerAddress() {
        return groupOwnerAddress;
    }

    public boolean getWifiDirectEnabled() {
        return wifiDirectEnabled;
    }

    public enum WifiDirectStatus {FAILED, INVITED, AVAILABLE, UNAVAILABLE, UNDEFINED, CONNECTED}

    public enum WifiDirectEventType {
        WIFI_DIRECT_MANAGER_INITIALIZED,
        WIFI_DIRECT_MANAGER_PEERS_CHANGED,
        WIFI_DIRECT_MANAGER_CONNECTION_CHANGED,
        WIFI_DIRECT_MANAGER_GROUP_INFO_CHANGED,
        WIFI_DIRECT_MANAGER_DISCOVERY_CHANGED,
        WIFI_DIRECT_MANAGER_DEVICE_INFO_CHANGED
    }

    private static class CompletableActionListener extends CompletableFuture<OptionalInt>
            implements WifiP2pManager.ActionListener {

        @Override
        public void onSuccess() {
            complete(OptionalInt.empty());
        }

        @Override
        public void onFailure(int reason) {

            complete(OptionalInt.of(reason));
        }
    }

    public record WifiDirectEvent(WifiDirectEventType type, String message) {}

    /**
     * A BroadcastReceiver that notifies of important wifi p2p events.
     * Acts as a event dispatcher to DeviceDetailFragment
     * Whenever a WifiDirect intent event is triggered this is where
     * we will handle the event
     */
    public class WifiDirectBroadcastReceiver extends BroadcastReceiver {
        private static final Logger logger = Logger.getLogger(WifiDirectBroadcastReceiver.class.getName());

        /**
         * Listener callback whenever one of the registered WifiDirect Intents
         * that were registered WifiDirectManager are triggered
         *
         * @param context Context/MainActivity where the intent is triggered
         * @param intent  Intent object containing triggered action.
         * @noinspection deprecation, deprecation
         */

        @RequiresPermission(allOf = { Manifest.permission.ACCESS_FINE_LOCATION,
                                      Manifest.permission.NEARBY_WIFI_DEVICES })
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                switch (action) {
                    case WIFI_P2P_STATE_CHANGED_ACTION -> {
                        // Broadcast intent action to indicate whether Wi-Fi p2p is enabled or disabled
                        // Check if WifiDirect on this device is turned on.
                        int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                        if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            logger.log(INFO, "WifiDirect enabled");
                            wifiDirectEnabled = true;
                            manager.requestDeviceInfo(channel, WifiDirectManager.this::processDeviceInfo);
                            manager.requestDiscoveryState(channel,
                                                          state1 -> discoveryActive =
                                                                  state1 == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED);
                        } else {
                            logger.log(INFO, "WifiDirect not enabled");
                            wifiDirectEnabled = false;
                        }
                        notifyActionToListeners(WifiDirectEventType.WIFI_DIRECT_MANAGER_INITIALIZED);
                    }
                    case WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        // Broadcast intent action indicating that the available peer list has changed.
                        // This can be sent as a result of peers being found, lost or updated.
                        try {
                            var newPeerList = intent.getParcelableExtra(EXTRA_P2P_DEVICE_LIST, WifiP2pDeviceList.class);
                            if (newPeerList != null) {
                                discoveredPeers = new HashSet<>(newPeerList.getDeviceList());
                            }
                            notifyActionToListeners(WifiDirectEventType.WIFI_DIRECT_MANAGER_PEERS_CHANGED);
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

                        WifiDirectManager.this.groupInfo = conGroup;
                        var ownerAddress = conInfo == null ? null : conInfo.groupOwnerAddress;
                        if (ownerAddress == null) {
                            if (groupOwnerAddress != null) {
                                groupOwnerAddress = null;
                                notifyActionToListeners(WifiDirectEventType.WIFI_DIRECT_MANAGER_CONNECTION_CHANGED);
                            }
                        } else {
                            if (!ownerAddress.equals(groupOwnerAddress)) {
                                groupOwnerAddress = ownerAddress;
                                notifyActionToListeners(WifiDirectEventType.WIFI_DIRECT_MANAGER_CONNECTION_CHANGED);
                            }
                        }
                        notifyActionToListeners(WifiDirectEventType.WIFI_DIRECT_MANAGER_GROUP_INFO_CHANGED);
                    }
                    case WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> processDeviceInfo(intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                            WifiP2pDevice.class));
                    case WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                        int discoveryState = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1);
                        discoveryActive = discoveryState == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED;
                        notifyActionToListeners(WifiDirectEventType.WIFI_DIRECT_MANAGER_DISCOVERY_CHANGED);
                    }
                }
            }
        }
    }
}

