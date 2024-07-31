package net.discdd.wifidirect;

import static net.discdd.wifidirect.WifiDirectManager.WifiDirectEventType.WIFI_DIRECT_MANAGER_CONNECTION_INITIATION_FAILED;
import static net.discdd.wifidirect.WifiDirectManager.WifiDirectEventType.WIFI_DIRECT_MANAGER_CONNECTION_INITIATION_SUCCESSFUL;
import static net.discdd.wifidirect.WifiDirectManager.WifiDirectEventType.WIFI_DIRECT_MANAGER_DISCOVERY_FAILED;
import static net.discdd.wifidirect.WifiDirectManager.WifiDirectEventType.WIFI_DIRECT_MANAGER_FORMED_CONNECTION_FAILED;
import static net.discdd.wifidirect.WifiDirectManager.WifiDirectEventType.WIFI_DIRECT_MANAGER_FORMED_CONNECTION_SUCCESSFUL;
import static net.discdd.wifidirect.WifiDirectManager.WifiDirectEventType.WIFI_DIRECT_MANAGER_INFO;
import static net.discdd.wifidirect.WifiDirectManager.WifiDirectEventType.WIFI_DIRECT_MANAGER_INITIALIZATION_FAILED;
import static net.discdd.wifidirect.WifiDirectManager.WifiDirectEventType.WIFI_DIRECT_MANAGER_INITIALIZATION_SUCCESSFUL;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.IntentFilter;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Main WifiDirect class
 * Contains wrapper methods around common WifiDirect tasks
 */
public class WifiDirectManager implements WifiP2pManager.ConnectionInfoListener, WifiP2pManager.PeerListListener {
    private static final Logger logger = Logger.getLogger(WifiDirectManager.class.getName());
    private final IntentFilter intentFilter = new IntentFilter();
    private final Context context;
    private final Lifecycle lifeCycle;
    private final List<WifiDirectStateListener> listeners = new ArrayList<>();
    private final boolean isOwner;
    private String deviceName;
    HashSet<WifiP2pDevice> connectedPeers = new HashSet<>();
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private WifiDirectBroadcastReceiver receiver;
    private String wifiDirectGroupHostIP;
    private String groupHostInfo;
    private boolean isConnected;
    private HashSet<WifiP2pDevice> discoveredPeers = new HashSet<>();

    /**
     * Ctor
     *
     * @param context   AppcompatActivity Context returned with a
     *                  AppCompatActivity.getApplication() call in
     *                  your main activity
     * @param lifeCycle AppActivity Lifecycle returned with a
     *                  AppCompatActivity.getLifeCycle() call in
     *                  your main activity
     */
    public WifiDirectManager(Context context, Lifecycle lifeCycle, WifiDirectStateListener listener,
                             String deviceName, boolean isOwner) {
        this.context = context;
        this.lifeCycle = lifeCycle;
        listeners.add(listener);
        this.deviceName = deviceName;
        this.isOwner = isOwner;
    }

    public boolean isOwner() {return isOwner;}

    public HashSet<WifiP2pDevice> getConnectedPeers() {
        return connectedPeers;
    }

    public void setConnectedPeers(Collection<WifiP2pDevice> clientList) {
        connectedPeers = new HashSet<>(clientList);
    }

    public boolean isConnected() {
        return isConnected;
    }

    public void setConnected(boolean isConnected) {
        this.isConnected = isConnected;
    }

    public void initialize() {
        this.registerIntents();
        this.manager = (WifiP2pManager) this.context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            logger.log(INFO, "Cannot get Wi-Fi system service");
        } else {
            notifyActionToListeners(WIFI_DIRECT_MANAGER_INFO, "trying to initialize");
            this.channel = this.manager.initialize(this.context, this.context.getMainLooper(),
                                                   () -> notifyActionToListeners(WIFI_DIRECT_MANAGER_INFO,
                                                                                 "Channel disconnected"));
            if (channel == null) {
                logger.log(WARNING, "Cannot initialize Wi-Fi Direct");
            }

        }

        this.receiver = new WifiDirectBroadcastReceiver(this);
        getContext().registerReceiver(getReceiver(), getIntentFilter());
        WifiDirectLifeCycleObserver lifeCycleObserver = new WifiDirectLifeCycleObserver(this);
        this.lifeCycle.addObserver(lifeCycleObserver);

        this.wifiDirectGroupHostIP = "";
        this.groupHostInfo = "";
        this.isConnected = false;
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

    /**
     * Discovers WifiDirect peers for this device.
     *
     * @return Completable Future true if discovery is successful false if not
     */

    @SuppressLint("MissingPermission")
    public void discoverPeers() {
        this.manager.discoverPeers(this.channel, new WifiP2pManager.ActionListener() {

            @Override
            public void onSuccess() {
                notifyActionToListeners(WifiDirectEventType.WIFI_DIRECT_MANAGER_DISCOVERY_SUCCESSFUL,
                                        "Peer discovery successful");
            }

            @Override
            public void onFailure(int reasonCode) {
                notifyActionToListeners(WIFI_DIRECT_MANAGER_DISCOVERY_FAILED,
                                        "Failed to discover peers with reasonCode: " + reasonCode);
            }
        });
    }

    // TODO: why do we need this function? should we be using it?

    /**
     * PeersListListener interface override function
     * Activation function is this.manager.discoverPeers();
     * Afterwards  WIFI_P2P_PEERS_CHANGED_ACTION in WifiDirectBroadcastReceiver
     * is activated and manager.requestPeers() is called which leads to this
     * function
     */

    @SuppressLint("MissingPermission")
    void requestPeers() {
        manager.requestPeers(channel, this);
    }

    @Override
    public void onPeersAvailable(WifiP2pDeviceList peers) {
        /*
        if (peers.getDeviceList().containsAll(discoveredPeers) &&
                discoveredPeers.containsAll(peers.getDeviceList())) {
            return;
        }
         */
        discoveredPeers = new HashSet<>(peers.getDeviceList());
        notifyActionToListeners(WifiDirectEventType.WIFI_DIRECT_MANAGER_PEERS_CHANGED);
    }

    /**
     * Create a WifiDirect group for other WifiDirect devices can connect to.
     */
    public CompletableFuture<Boolean> createGroup(String networkName, String password) {
        WifiP2pConfig config = this.buildGroupConfig(networkName, password);
        CompletableFuture<Boolean> cFuture = new CompletableFuture<>();

        return cFuture;
    }

    public void createGroup() {
        WifiP2pConfig.Builder config = new WifiP2pConfig.Builder();
        config.setNetworkName("DIRECT-DDD-" + deviceName);
        // the transports are not trusted. secrets are meaningless
        config.setPassphrase("DDDme-NOTSECRET!");
        this.manager.createGroup(this.channel, config.build(), new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                notifyActionToListeners(WIFI_DIRECT_MANAGER_FORMED_CONNECTION_SUCCESSFUL,
                                        "Group created for " + deviceName);
            }

            @Override
            public void onFailure(int reasonCode) {
                notifyActionToListeners(WIFI_DIRECT_MANAGER_FORMED_CONNECTION_FAILED,
                                        "Could not create group for " + deviceName + " rc = " + reasonCode);
                logger.log(WARNING, "Failed to create a group with reasonCode: " + reasonCode);
            }
        });
    }

    /**
     * Build a WifiDirect group config allowing us to setup our
     * group name and password
     *
     * @param networkName Name of the network must begin with DIRECT-XY
     * @param password    Password must be between 8 - 64 characters long
     * @return
     */
    private WifiP2pConfig buildGroupConfig(String networkName, String password) {
        try {
            WifiP2pConfig.Builder cBuilder = new WifiP2pConfig.Builder();
            cBuilder.enablePersistentMode(false);
            cBuilder.setNetworkName(networkName);
            cBuilder.setPassphrase(password);
            return cBuilder.build();
        } catch (Exception e) {
            logger.log(WARNING, "BuildGroupConfigexception " + e);
        }
        return null;
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
        this.manager.requestGroupInfo(channel, cFuture::complete);
        return cFuture;
    }

    /**
     * Directly connect to a device with this WifiP2pConfig
     *
     * @param config Config of device to connect to
     * @return Future containing true if WifiDirect connection successful false if not
     */
    public CompletableFuture<Boolean> connect(WifiP2pDevice device) {
        CompletableFuture<Boolean> completableFuture = new CompletableFuture<>();
        var config = new WifiP2pConfig();
        config.wps.setup = WpsInfo.PBC;
        config.deviceAddress = device.deviceAddress;
        this.manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                completableFuture.complete(true);
                notifyActionToListeners(WIFI_DIRECT_MANAGER_CONNECTION_INITIATION_SUCCESSFUL);
            }

            @Override
            public void onFailure(int reasonCode) {
                completableFuture.complete(false);
                notifyActionToListeners(WIFI_DIRECT_MANAGER_CONNECTION_INITIATION_FAILED);
            }
        });
        return completableFuture;
    }

    /**
     * Get the config of this WifiP2pDevice
     *
     * @param device  WifiDirect device we want to make a config of
     * @param isOwner true if we want THIS device to be host false if
     *                the device we are connecting to is going to be the host
     * @return WifiP2PConfig ready to be called by manager.connect()
     */
    private WifiP2pConfig makeConfig(WifiP2pDevice device, boolean isOwner) {
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.wps.setup = WpsInfo.PBC;
        return config;
    }

    /**
     * In  WIFI_P2P_CONNECTION_CHANGED_ACTION
     *
     * @param info
     */
    @Override
    public void onConnectionInfoAvailable(final WifiP2pInfo info) {
        String hostIP = info.groupOwnerAddress.getHostAddress();
        this.groupHostInfo = info.toString();
        this.wifiDirectGroupHostIP = hostIP;

        if (info.groupFormed && info.isGroupOwner) {
            // Do whatever tasks are specific to the group owner.
            // One common case is creating a group owner thread and accepting
            // incoming connections.
            notifyActionToListeners(WIFI_DIRECT_MANAGER_FORMED_CONNECTION_SUCCESSFUL);
        } else if (info.groupFormed) {
            // The other device acts as the peer (client). In this case,
            // you'll want to create a peer thread that connects
            // to the group owner.
            notifyActionToListeners(WIFI_DIRECT_MANAGER_FORMED_CONNECTION_SUCCESSFUL);
        } else {
            notifyActionToListeners(WIFI_DIRECT_MANAGER_FORMED_CONNECTION_FAILED);
        }

    }

    /**
     * "I want to see your manager"
     *
     * @return this manager's manager
     */
    public WifiP2pManager getManager() {
        return this.manager;
    }

    /**
     * Get this manager's Activity Channel
     *
     * @return this WifiP2pManager.Channel
     */
    public WifiP2pManager.Channel getChannel() {
        return this.channel;
    }

    /**
     * Get this manager's IntentFilter with the intents registered
     *
     * @return this IntentFilter
     */
    public IntentFilter getIntentFilter() {return this.intentFilter;}

    /**
     * Get the BroadCastReceiver
     *
     * @return the WifiDirectBroadcastReceiver
     */
    public WifiDirectBroadcastReceiver getReceiver() {return this.receiver;}

    /**
     * Get the App context associated with this WifiDirectManager
     *
     * @return the app context
     */
    public Context getContext() {return this.context;}

    public void addListener(WifiDirectStateListener listener) {
        listeners.add(listener);
    }

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

    public void wifiDirectEnabled(boolean enabled) {
        notifyActionToListeners(
                enabled ? WIFI_DIRECT_MANAGER_INITIALIZATION_SUCCESSFUL : WIFI_DIRECT_MANAGER_INITIALIZATION_FAILED,
                "wifi enabled " + enabled);
    }

    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public void disconnect(WifiP2pDevice device) {
        manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                logger.info("Disconnected from " + device.deviceName);
            }

            @Override
            public void onFailure(int reason) {
                logger.warning("Failed to disconnect from " + device.deviceName + " with reason " + reason);
            }
        });
        discoverPeers();
    }

    public enum WifiDirectEventType {
        WIFI_DIRECT_MANAGER_INITIALIZATION_SUCCESSFUL, WIFI_DIRECT_MANAGER_INITIALIZATION_FAILED,
        WIFI_DIRECT_MANAGER_DISCOVERY_SUCCESSFUL, WIFI_DIRECT_MANAGER_DISCOVERY_FAILED,
        WIFI_DIRECT_MANAGER_PEERS_CHANGED, WIFI_DIRECT_MANAGER_CONNECTION_INITIATION_FAILED,
        WIFI_DIRECT_MANAGER_CONNECTION_INITIATION_SUCCESSFUL, WIFI_DIRECT_MANAGER_FORMED_CONNECTION_SUCCESSFUL,
        WIFI_DIRECT_MANAGER_FORMED_CONNECTION_FAILED, WIFI_DIRECT_MANAGER_INFO,
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

        /**
         * Ctor
         *
         * @param manager WifiDrectManager outer class
         */
        public WifiDirectLifeCycleObserver(WifiDirectManager manager) {
            this.manager = manager;
        }

        /**
         * Register the receiver on app open
         *
         * @param owner app's main activity
         */
        @Override
        public void onResume(@NonNull LifecycleOwner owner) {
            this.manager.getContext().registerReceiver(this.manager.getReceiver(), this.manager.getIntentFilter());
        }

        /**
         * Unregister the receiver when app suspended
         */
        @Override
        public void onPause(@NonNull LifecycleOwner owner) {
            this.manager.getContext().unregisterReceiver(receiver);
        }
    }
}

