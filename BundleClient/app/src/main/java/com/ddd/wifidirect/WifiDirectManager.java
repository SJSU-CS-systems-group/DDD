package com.ddd.wifidirect;

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
import android.os.Build;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.ddd.bundleclient.HelloworldActivity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;


/**
 * Main WifiDirect class
 * Contains wrapper methods around common WifiDirect tasks
 */
public class WifiDirectManager implements WifiP2pManager.ConnectionInfoListener, WifiP2pManager.PeerListListener {

    public enum WIFI_DIRECT_ACTIONS {
        WIFI_DIRECT_MANAGER_DISCOVERY_SUCCESSFUL,
        WIFI_DIRECT_MANAGER_DISCOVERY_FAILED,
        WIFI_DIRECT_MANAGER_PEERS_CHANGED,
        WIFI_DIRECT_MANAGER_CONNECTION_INITIATION_FAILED,
        WIFI_DIRECT_MANAGER_CONNECTION_INITIATION_SUCCESSFUL,
        WIFI_DIRECT_MANAGER_FORMED_CONNECTION_SUCCESSFUL,
        WIFI_DIRECT_MANAGER_FORMED_CONNECTION_FAILED
    }


    private final IntentFilter intentFilter = new IntentFilter();

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private Context context;
    private Lifecycle lifeCycle;
    private WifiDirectLifeCycleObserver lifeCycleObserver;
    private WifiDirectBroadcastReceiver receiver;
    private List<WifiP2pDevice> peerList;
    private String wifiDirectGroupHostIP;
    private String groupHostInfo;
    private String deviceName;
    private boolean isConnected;

    private List<WifiDirectStateListener> listeners = new ArrayList<>();

    /**
     * Ctor
     *
     * @param context AppcompatActivity Context returned with a
     *                AppCompatActivity.getApplication() call in
     *                your main activity
     * @param lifeCycle AppActivity Lifecycle returned with a
     *                  AppCompatActivity.getLifeCycle() call in
     *                  your main activity
     */
    public WifiDirectManager(Context context, Lifecycle lifeCycle, WifiDirectStateListener listener, String deviceName) {
        this.context = context;
        listeners.add(listener);
        this.initClient(this.context);
        this.registerIntents();

        this.lifeCycle = lifeCycle;
        this.receiver = createReceiver();

        this.lifeCycleObserver = new WifiDirectLifeCycleObserver(this);
        this.lifeCycle.addObserver(lifeCycleObserver);

        this.peerList = new ArrayList<WifiP2pDevice>();
        this.wifiDirectGroupHostIP = "";
        this.groupHostInfo ="";
        this.deviceName = deviceName;
        this.isConnected = false;
    }

    /**
     * Initialize this WifiDirect device as a peer device
     * Right now no difference between owner and peer
     * @param context AppcompatActivity Context
     */
    private void initClient(Context context) {
        this.manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        this.channel = this.manager.initialize(context, Looper.getMainLooper(), null);
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
     * @return Completable Future true if discovery is successful false if not
     */

    @SuppressLint("MissingPermission")
    public void discoverPeers() {
        this.manager.discoverPeers(this.channel, new WifiP2pManager.ActionListener() {

            @Override
            public void onSuccess() {
                notifyActionToListeners(WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_DISCOVERY_SUCCESSFUL);
            }

            @Override
            public void onFailure(int reasonCode) {
                notifyActionToListeners(WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_DISCOVERY_FAILED);;
            }
        });
    }

    /**
     * PeersListListener interface override function
     * Activation function is this.manager.discoverPeers();
     * Afterwards  WIFI_P2P_PEERS_CHANGED_ACTION in WifiDirectBroadcastReceiver
     * is activated and manager.requestPeers() is called which leads to this
     * function
     *
     * @param deviceList
     */

    @SuppressLint("MissingPermission")
    public void requestPeers(){
        manager.requestPeers(channel, this);
    }

    @Override
    public void onPeersAvailable(WifiP2pDeviceList peers) {
        Collection<WifiP2pDevice> newList = peers.getDeviceList();
        if(!newList.equals(peerList)) {
            peerList.clear();
            peerList.addAll(newList);
            notifyActionToListeners(WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_PEERS_CHANGED);
            filterPeersAndConnect();
        }
    }

    /**
     * Create a WifiDirect group for other WifiDirect devices can connect to.
     * @return true if discovery is successful false if not
     */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    public CompletableFuture<Boolean> createGroup(String networkName, String password) {
        WifiP2pConfig config = this.buildGroupConfig(networkName,
                password);
        CompletableFuture<Boolean> cFuture = new CompletableFuture<>();
        this.manager.createGroup(this.channel, config, new WifiP2pManager.ActionListener() {

            @Override
            public void onSuccess() {
                cFuture.complete(true);
            }

            @Override
            public void onFailure(int reasonCode) {

                Log.d(HelloworldActivity.TAG,"Failed to create a group with reasonCode: " + reasonCode);
                cFuture.complete(false);
            }
        });
        return cFuture;
    }

    /**
     * Build a WifiDirect group config allowing us to setup our
     * group name and password
     * @param networkName Name of the network must begin with DIRECT-XY
     * @param password Password must be between 8 - 64 characters long
     * @return
     */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private WifiP2pConfig buildGroupConfig(String networkName, String password) {
        try {
            WifiP2pConfig.Builder cBuilder = new WifiP2pConfig.Builder();
            cBuilder.enablePersistentMode(false);
            cBuilder.setNetworkName(networkName);
            cBuilder.setPassphrase(password);
            return cBuilder.build();
        }
        catch(Exception e) {
            Log.d(HelloworldActivity.TAG, "BuildGroupConfigexception " + e) ;
        }
        return null;
    }

    /**
     * Leave the current WifiDirect Group this device is connected to
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
                Log.d(HelloworldActivity.TAG,"Failed to remove a group with reasonCode: " +
                        reasonCode + " Note: this could mean device was never part of a group");
                cFuture.complete(false);
            }
        });
        return cFuture;
    }

    /**
     * Get the GroupInfo of current WifiDirect devices connected to this group.
     * @return WifiP2PGroup object containing list of connected devices.
     */
    @SuppressLint("MissingPermission")
    public CompletableFuture<WifiP2pGroup> requestGroupInfo() {
        CompletableFuture<WifiP2pGroup> cFuture = new CompletableFuture<>();
        this.manager.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
            @Override
            public void onGroupInfoAvailable(WifiP2pGroup group) {
                cFuture.complete(group);
            }
        });
        return cFuture;
    }

    private void filterPeersAndConnect(){
        for (WifiP2pDevice peer: peerList){
            if (peer.deviceName.contains(deviceName)){
                connect(makeConfig(peer, false));
            }
        }
    }

    /**
     * Directly connect to a device with this WifiP2pConfig
     * @param config Config of device to connect to
     * @return Future containing true if WifiDirect connection successful false if not
     */
    @SuppressLint("MissingPermission")
    private void connect(WifiP2pConfig config) {
        config.groupOwnerIntent = 0;
        this.manager.connect(channel, config, new WifiP2pManager.ActionListener() {

            @Override
            public void onSuccess() {
                notifyActionToListeners(WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_CONNECTION_INITIATION_SUCCESSFUL);
            }

            @Override
            public void onFailure(int reasonCode) {
                notifyActionToListeners(WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_CONNECTION_INITIATION_FAILED);
            }
        });
    }


    /**
     * Get the config of this WifiP2pDevice
     * @param device  WifiDirect device we want to make a config of
     * @param isOwner true if we want THIS device to be host false if
     *                the device we are connecting to is going to be the host
     * @return WifiP2PConfig ready to be called by manager.connect()
     */
    public WifiP2pConfig makeConfig(WifiP2pDevice device, boolean isOwner) {
        WifiP2pConfig config =  new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.wps.setup = WpsInfo.PBC;
//            if(isOwner) {
//                config.groupOwnerIntent = WifiP2pConfig.GROUP_OWNER_INTENT_MAX;
//            }
//            else {
//                config.groupOwnerIntent = WifiP2pConfig.GROUP_OWNER_INTENT_MIN;
//            }
        return config;
    }

    /**
     * In  WIFI_P2P_CONNECTION_CHANGED_ACTION
     * @param info
     */
    @Override
    public void onConnectionInfoAvailable(final WifiP2pInfo info) {
        String hostIP = info.groupOwnerAddress.getHostAddress();
        this.groupHostInfo = info.toString();
        this.wifiDirectGroupHostIP = hostIP;
        Log.d(HelloworldActivity.TAG, "Connected device to "+hostIP);

        if (info.groupFormed && info.isGroupOwner) {
            // Do whatever tasks are specific to the group owner.
            // One common case is creating a group owner thread and accepting
            // incoming connections.
            notifyActionToListeners(WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_FORMED_CONNECTION_SUCCESSFUL);
        } else if (info.groupFormed) {
            // The other device acts as the peer (client). In this case,
            // you'll want to create a peer thread that connects
            // to the group owner.
            notifyActionToListeners(WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_FORMED_CONNECTION_SUCCESSFUL);
        } else {
            notifyActionToListeners(WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_FORMED_CONNECTION_FAILED);
        }

    }


    /**
     * "I want to see your manager"
     * @return this manager's manager
     */
    public WifiP2pManager getManager() {
        return this.manager;
    }

    /**
     * Get this manager's Activity Channel
     * @return this WifiP2pManager.Channel
     */
    public WifiP2pManager.Channel getChannel() {
        return this.channel;
    }

    /**
     * Get this manager's IntentFilter with the intents registered
     * @return this IntentFilter
     */
    public IntentFilter getIntentFilter() { return this.intentFilter; }

    /**
     * Get the BroadCastReceiver
     * @return the WifiDirectBroadcastReceiver
     */
    public WifiDirectBroadcastReceiver getReceiver() { return this.receiver; }

    public WifiDirectBroadcastReceiver createReceiver() {
        return new WifiDirectBroadcastReceiver(this);
    }

    /**
     * Get the App context associated with this WifiDirectManager
     * @return the app context
     */
    public Context getContext() { return this.context; }



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
         * @param manager WifiDrectManager outer class
         */
        public WifiDirectLifeCycleObserver(WifiDirectManager manager) {
            this.manager = manager;
        }
        /**
         * Register the receiver on app open
         * @param owner app's main activity
         */
        @Override
        public void onResume(@NonNull LifecycleOwner owner) {
            this.manager.getContext().registerReceiver(this.manager.getReceiver(), this.manager.getIntentFilter());
        }

        /**
         * Unregister the receiver when app suspended
         * @param owner
         */
        @Override
        public void onPause(@NonNull LifecycleOwner owner) {
            this.manager.getContext().unregisterReceiver(receiver);
        }
    }

    public void addListener(WifiDirectStateListener listener) {
        listeners.add(listener);
    }

    private void notifyActionToListeners(WIFI_DIRECT_ACTIONS action) {
        for (WifiDirectStateListener listener: listeners) {
            listener.onReceiveAction(action);
        }
    }
}

