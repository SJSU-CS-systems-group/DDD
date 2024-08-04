package net.discdd.wifidirect;

import static android.net.wifi.p2p.WifiP2pManager.EXTRA_P2P_DEVICE_LIST;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION;
import static net.discdd.wifidirect.WifiDirectManager.WifiDirectEventType.WIFI_DIRECT_MANAGER_DEVICE_INFO_CHANGED;
import static net.discdd.wifidirect.WifiDirectManager.WifiDirectEventType.WIFI_DIRECT_MANAGER_INITIALIZED;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    private final Lifecycle lifeCycle;
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

    /**
     * @param context   AppcompatActivity Context returned with a
     *                  AppCompatActivity.getApplication() call in
     *                  your main activity
     * @param lifeCycle AppActivity Lifecycle returned with a
     *                  AppCompatActivity.getLifeCycle() call in
     *                  your main activity
     */
    public WifiDirectManager(Context context, Lifecycle lifeCycle,
                             WifiDirectStateListener listener, boolean isOwner) {
        this.context = context;
        this.lifeCycle = lifeCycle;
        listeners.add(listener);
        this.isOwner = isOwner;
    }

    public void initialize() {
        this.registerIntents();
        this.manager = (WifiP2pManager) this.context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            logger.log(INFO, "Cannot get Wi-Fi system service");
        } else {
            this.channel = this.manager.initialize(this.context, this.context.getMainLooper(),
                                                   () -> notifyActionToListeners(
                                                           WIFI_DIRECT_MANAGER_INITIALIZED,
                                                           "Channel disconnected"));
            if (channel == null) {
                logger.log(WARNING, "Cannot initialize Wi-Fi Direct");
            }

        }

        this.receiver = new WifiDirectBroadcastReceiver();
        registerWifiIntentReceiver();
        if (lifeCycle != null) {
            WifiDirectLifeCycleObserver lifeCycleObserver = new WifiDirectLifeCycleObserver(this);
            this.lifeCycle.addObserver(lifeCycleObserver);
        }

        manager.requestDeviceInfo(channel, this::processDeviceInfo);
    }

    // package protected so that the broadcast receiver can call it
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

    /**
     * Register Android Intents more commonly known as events we
     * want to listen to for this device.
     */
    private void registerIntents() {
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
    }

    public String getDeviceName() {
        return deviceName;
    }

    public WifiDirectStatus getStatus() {
        return status;
    }

    /**
     * Discovers WifiDirect peers for this device.
     *
     * @return Completable Future true if discovery is successful false if not
     */

    @SuppressLint("MissingPermission")
    public CompletableFuture<Boolean> discoverPeers() {
        CompletableFuture<Boolean> completableFuture = new CompletableFuture<>();
        this.manager.discoverPeers(this.channel, new WifiP2pManager.ActionListener() {

            @Override
            public void onSuccess() {
                completableFuture.complete(true);
            }

            @Override
            public void onFailure(int reasonCode) {
                completableFuture.complete(false);
            }
        });
        return completableFuture;
    }

    /**
     * PeersListListener interface override function
     * Activation function is this.manager.discoverPeers();
     * Afterwards  WIFI_P2P_PEERS_CHANGED_ACTION in WifiDirectBroadcastReceiver
     * is activated and manager.requestPeers() is called which leads to this
     * function
     */

    @SuppressLint("MissingPermission")
    CompletableFuture<HashSet<WifiP2pDevice>> requestPeers() {
        var completableFuture = new CompletableFuture<HashSet<WifiP2pDevice>>();
        manager.requestPeers(channel, peers -> {
            discoveredPeers = new HashSet<>(peers.getDeviceList());
            notifyActionToListeners(WifiDirectEventType.WIFI_DIRECT_MANAGER_PEERS_CHANGED);
            completableFuture.complete(discoveredPeers);
        });
        return completableFuture;
    }

    public CompletableFuture<WifiP2pGroup> createGroup() {
        var completableFuture = new CompletableFuture<WifiP2pGroup>();
        this.manager.createGroup(this.channel, new WifiP2pManager.ActionListener() {
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
     * Leave the current WifiDirect Group this device is connected to
     *
     * @return Future containing true if successful false if not
     */
    public CompletableFuture<Boolean> removeGroup() {
        CompletableFuture<Boolean> cFuture = new CompletableFuture<>();
        this.manager.removeGroup(this.channel, new WifiP2pManager.ActionListener() {

            @Override
            public void onSuccess() {
                cFuture.complete(true);
            }

            @Override
            public void onFailure(int reasonCode) {
                logger.log(WARNING, "Failed to remove a group with reasonCode: " + reasonCode +
                        " Note: this could mean device was never part of a group");
                cFuture.complete(false);
            }
        });
        return cFuture;
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
                logger.warning("Failed to disconnect from with reason " +
                                       reason);
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

    public CompletableFuture<Void> requestDeviceInfo() {
        CompletableFuture<Void> completableFuture = new CompletableFuture<>();
        manager.requestDeviceInfo(channel, di -> {
            processDeviceInfo(di);
            completableFuture.complete(null);
        });
        return completableFuture;
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
        WIFI_DIRECT_MANAGER_DEVICE_INFO_CHANGED
    }

    public record WifiDirectEvent(WifiDirectEventType type, String message) {}

    /**
     * Inner Class to hook into activity Lifecycle functions
     * and register/unregister BroadcastReceiver
     * Note this may need to be modified when turning WifiDirectManager into
     * a service
     */
    private class WifiDirectLifeCycleObserver implements DefaultLifecycleObserver {
        public WifiDirectManager manager;

        public WifiDirectLifeCycleObserver(WifiDirectManager manager) {
            this.manager = manager;
        }

        @Override
        public void onResume(@NonNull LifecycleOwner owner) {
            registerWifiIntentReceiver();
        }

        @Override
        public void onPause(@NonNull LifecycleOwner owner) {
            unregisterWifiIntentReceiver();
        }
    }

    /**
     * A BroadcastReceiver that notifies of important wifi p2p events.
     * Acts as a event dispatcher to DeviceDetailFragment
     * Whenever a WifiDirect intent event is triggered this is where
     * we will handle the event
     */
    public class WifiDirectBroadcastReceiver extends BroadcastReceiver {
        private static final Logger logger =
                Logger.getLogger(WifiDirectBroadcastReceiver.class.getName());

        /**
         * Listener callback whenever one of the registered WifiDirect Intents
         * that were registered WifiDirectManager are triggered
         *
         * @param context Context/MainActivity where the intent is triggered
         * @param intent  Intent object containing triggered action.
         */

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            // Broadcast intent action to indicate whether Wi-Fi p2p is enabled or disabled
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                // Check if WifiDirect on this device is turned on.
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    logger.log(INFO, "WifiDirect enabled");
                    wifiDirectEnabled = true;
                } else {
                    logger.log(INFO, "WifiDirect not enabled");
                    wifiDirectEnabled = false;
                }
            }
            // Broadcast intent action indicating that the available peer list has changed.
            // This can be sent as a result of peers being found, lost or updated.
            else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
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
            //         Broadcast intent action indicating that the state of Wi-Fi p2p
            //         connectivity has changed.
            //         EXTRA_WIFI_P2P_INFO provides the p2p connection info in the form of a
            //         WifiP2pInfo object.
            //         Another extra EXTRA_NETWORK_INFO provides the network info in the form of
            //         a NetworkInfo.
            //         A third extra provides the details of the EXTRA_WIFI_P2P_GROUP and may
            //         contain a null
            else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                WifiP2pInfo info =
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO,
                                                  WifiP2pInfo.class);
                NetworkInfo networkInfo =
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                                                  NetworkInfo.class);
                WifiP2pGroup wifiP2pGroup =
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP,
                                                  WifiP2pGroup.class);

                var groupInfoChanged = false;
                if (groupInfo == null || !groupInfo.equals(wifiP2pGroup)) {
                    groupInfo = wifiP2pGroup;
                    groupInfoChanged = true;
                }
                if (networkInfo != null && networkInfo.isConnected()) {
                    var newGroupOwnerAddress = info == null ? null : info.groupOwnerAddress;
                    if ((groupOwnerAddress != null  && !groupOwnerAddress.equals(newGroupOwnerAddress)
                            || (groupOwnerAddress == null && newGroupOwnerAddress != null))) {
                        groupOwnerAddress = newGroupOwnerAddress;
                        groupInfoChanged = true;
                        notifyActionToListeners(WifiDirectEventType.WIFI_DIRECT_MANAGER_CONNECTION_CHANGED);
                    }
                } else {
                    if (groupOwnerAddress != null) {
                        groupOwnerAddress = null;
                        groupInfoChanged = true;
                    }
                }
                if (groupInfoChanged) {
                    notifyActionToListeners(WifiDirectEventType.WIFI_DIRECT_MANAGER_GROUP_INFO_CHANGED);
                }
            } else if (WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                processDeviceInfo(intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                                                            WifiP2pDevice.class));
            }
        }
    }
}

