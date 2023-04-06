package com.ddd.wifidirect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.util.Log;

import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.ddd.bundletransport.MainActivity;
import com.ddd.bundletransport.RpcServerWorker;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * A BroadcastReceiver that notifies of important wifi p2p events.
 * Acts as a event dispatcher to DeviceDetailFragment
 * Whenever a WifiDirect intent event is triggered this is where
 * we will handle the event
 */
public class WifiDirectBroadcastReceiver extends BroadcastReceiver {

    private WifiDirectManager manager;

    /**
     * ctor
     * @param manager WifiDirectManager
     */
    public WifiDirectBroadcastReceiver(WifiDirectManager manager) {
        super();
        this.manager = manager;
    }

    /**
     * Listener callback whenever one of the registered WifiDirect Intents
     * that were registered WifiDirectManager are triggered
     * @param context Context/MainActivity where the intent is triggered
     * @param intent Intent object containing triggered action.
     */

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        // Broadcast intent action to indicate whether Wi-Fi p2p is enabled or disabled
        if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {

            // Check if WifiDirect on this device is turned on.
            int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
            if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                Log.d(MainActivity.TAG, "P2P state changed Wifi Direct is enabled - " + state);
                // auto start gRPC server when app is opened
                    Log.d(MainActivity.TAG, "Starting server, at least one device is connected to group");
                    Data data = new Data.Builder().putInt("PORT", 777).build();
                    PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                            RpcServerWorker.class,
                            15, TimeUnit.MINUTES,
                            15, TimeUnit.MINUTES)
                            .setInputData(data)
                            .build();
                    WorkManager.getInstance(context)
                            .enqueueUniquePeriodicWork(MainActivity.TAG,
                                    ExistingPeriodicWorkPolicy.REPLACE, request);
            } else {
                Log.d(MainActivity.TAG, "P2P state changed Wifi Direct is not enabled - " + state);
            }

        }
        // Broadcast intent action indicating that the available peer list has changed.
        // This can be sent as a result of peers being found, lost or updated.
        else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
            Log.d(MainActivity.TAG, "WifiDirectBroadcastReceiver INTENT PEERS_CHANGED");
            ArrayList<WifiP2pDevice> newPeers = manager.getPeerList();
            //stop the gRPC server when  no devices are connected
//            if(newPeers.size() == 0){
//                Log.d(MainActivity.TAG, "Stopping server, no devices are connected to group");
//                WorkManager.getInstance(context).cancelUniqueWork(MainActivity.TAG);
//            }
        }
//         Broadcast intent action indicating that the state of Wi-Fi p2p
//         connectivity has changed.
//         EXTRA_WIFI_P2P_INFO provides the p2p connection info in the form of a WifiP2pInfo object.
//         Another extra EXTRA_NETWORK_INFO provides the network info in the form of a NetworkInfo.
//         A third extra provides the details of the EXTRA_WIFI_P2P_GROUP and may contain a null
        else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
            Log.d(MainActivity.TAG, "WifiDirect group connection CHANGED!");
            if (manager == null) {
                return;
            }

            // Gets the wifiP2pGroup
            // Not needed at this time
            // WifiP2pGroup group = (WifiP2pGroup)  intent.
                    //getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP);

            NetworkInfo networkInfo = (NetworkInfo) intent
                    .getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);

            if (networkInfo.isConnected()) {
                // we are connected, request connection
                // info to find group owner IP
                this.manager.setConnected(true);
               this.manager.getManager().requestConnectionInfo(this.manager.getChannel(), this.manager);
                Log.d(MainActivity.TAG,"WIFI_P2P_CONNECTION_CHANGED_ACTION connected");
            }
        else {
                // It's a disconnect
                this.manager.setConnected(false);
                Log.d(MainActivity.TAG,
                        "WIFI_P2P_CONNECTION_CHANGED_ACTION disconnected");
            }
        }
        else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
            // unneeded
            // was a UI update in the orginal WifiDirect example
        }
    }
}