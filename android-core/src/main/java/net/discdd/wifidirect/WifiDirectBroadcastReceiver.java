package net.discdd.wifidirect;

import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION;
import static net.discdd.wifidirect.WifiDirectManager.WifiDirectEventType.WIFI_DIRECT_MANAGER_FORMED_CONNECTION_SUCCESSFUL;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;

import java.util.logging.Logger;

/**
 * A BroadcastReceiver that notifies of important wifi p2p events.
 * Acts as a event dispatcher to DeviceDetailFragment
 * Whenever a WifiDirect intent event is triggered this is where
 * we will handle the event
 */
public class WifiDirectBroadcastReceiver extends BroadcastReceiver {
    private static final Logger logger = Logger.getLogger(WifiDirectBroadcastReceiver.class.getName());

    final private WifiDirectManager manager;

    public static Context ApplicationContext;

    /**
     * ctor
     *
     * @param manager WifiDirectManager
     */
    public WifiDirectBroadcastReceiver(WifiDirectManager manager) {
        super();
        this.manager = manager;
    }

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
        ApplicationContext = context;
        // Broadcast intent action to indicate whether Wi-Fi p2p is enabled or disabled
        if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {

            // Check if WifiDirect on this device is turned on.
            int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
            if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                logger.log(INFO, "WifiDirect enabled");
                manager.wifiDirectEnabled(true);
            } else {
                logger.log(INFO, "WifiDirect not enabled");
                manager.wifiDirectEnabled(false);
            }

        }
        // Broadcast intent action indicating that the available peer list has changed.
        // This can be sent as a result of peers being found, lost or updated.
        else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
            try {
                manager.requestPeers();
            } catch (SecurityException e) {
                logger.log(SEVERE, "SecurityException in requestPeers", e);
            }
        }
//         Broadcast intent action indicating that the state of Wi-Fi p2p
//         connectivity has changed.
//         EXTRA_WIFI_P2P_INFO provides the p2p connection info in the form of a WifiP2pInfo object.
//         Another extra EXTRA_NETWORK_INFO provides the network info in the form of a NetworkInfo.
//         A third extra provides the details of the EXTRA_WIFI_P2P_GROUP and may contain a null
        else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
            WifiP2pInfo wifiP2pInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo.class);
            NetworkInfo networkInfo =
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, NetworkInfo.class);
            WifiP2pGroup wifiP2pGroup =
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP, WifiP2pGroup.class);
            
            if (networkInfo != null && networkInfo.isConnected()) {
                // we are connected, request connection
                // info to find group owner IP
                this.manager.setConnected(true);
                logger.log(INFO, "WIFI_P2P_CONNECTION_CHANGED_ACTION connected");
                manager.notifyActionToListeners(WIFI_DIRECT_MANAGER_FORMED_CONNECTION_SUCCESSFUL);
                if (wifiP2pGroup != null) {
                    logger.log(INFO, "WifiP2pGroup client list changed");
                    this.manager.setConnectedPeers(wifiP2pGroup.getClientList());
                    this.manager.notifyActionToListeners(WIFI_DIRECT_MANAGER_FORMED_CONNECTION_SUCCESSFUL);
                }
            } else {
                // It's a disconnect
                manager.notifyActionToListeners(WIFI_DIRECT_MANAGER_FORMED_CONNECTION_SUCCESSFUL);
                this.manager.setConnected(false);
                logger.log(INFO, "WIFI_P2P_CONNECTION_CHANGED_ACTION disconnected");
            }
        } else if (WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
            if (manager.isOwner()) manager.createGroup();
            // unneeded
            // was a UI update in the orginal WifiDirect example
        }
    }
}
