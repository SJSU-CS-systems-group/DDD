package net.discdd.bundletransport.wifi;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import androidx.annotation.RequiresPermission;
import net.discdd.bundletransport.service.DDDWifiServiceEvents;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

public class DDDWifiServer {
    private static final Logger logger = Logger.getLogger(DDDWifiServer.class.getName());
    private final Context context;
    private final IntentFilter intentFilter = new IntentFilter();
    private WifiP2pManager wifiP2pManager;
    private WifiP2pManager.Channel channel;
    private WifiP2pGroup wifiGroup;
    private String deviceName;
    private WifiDirectBroadcastReceiver receiver;
    private boolean wifiDirectEnabled;
    private WifiDirectStatus status = WifiDirectStatus.UNDEFINED;

    public enum WifiDirectStatus {FAILED, INVITED, AVAILABLE, UNAVAILABLE, UNDEFINED, CONNECTED}

    public DDDWifiServer(Context applicationContext) {
        intentFilter.addAction(WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        this.context = applicationContext;
    }

    public DDDWifiNetworkInfo getNetworkInfo() {
        if (wifiGroup == null) {
            return null;
        }
        InetAddress inetAddress;

        try {
            var ni = NetworkInterface.getByName(wifiGroup.getInterface());
            inetAddress = ni == null ?
                          null :
                          Collections.list(ni.getInetAddresses())
                                  .stream()
                                  .filter(a -> a instanceof Inet4Address)
                                  .findFirst()
                                  .orElse(null);
        } catch (Exception e) {
            logger.severe("Error getting network interface: " + e.getMessage());
            inetAddress = null;
        }
        var clientList =
                wifiGroup.getClientList().stream().map(d -> d.deviceName).collect(Collectors.toUnmodifiableList());
        return new DDDWifiNetworkInfo(wifiGroup.getNetworkName(), wifiGroup.getPassphrase(), inetAddress, clientList);
    }

    // TODO
    public void shutdown() {}

    public String getDeviceName() {
        return deviceName;
    }

    public void sendStateChange() {
        DDDWifiServiceEvents.sendEvent(new DDDWifiServerEvent(DDDWifiServerEventType.DDDWIFISERVER_NETWORKINFO_CHANGED,
                                                              null));
    }

    public void sendDeviceNameChange() {
        DDDWifiServiceEvents.sendEvent(new DDDWifiServerEvent(DDDWifiServerEventType.DDDWIFISERVER_DEVICENAME_CHANGED,
                                                              null));
    }

    public void sendMessage(String message) {
        DDDWifiServiceEvents.sendEvent(new DDDWifiServerEvent(DDDWifiServerEventType.DDDWIFISERVER_MESSAGE, message));
    }

    @SuppressLint("MissingPermission")
    public void initialize() {
        this.wifiP2pManager = (WifiP2pManager) this.context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (wifiP2pManager == null) {
            logger.log(INFO, "Cannot get Wi-Fi system service");
        } else {
            this.channel =
                    this.wifiP2pManager.initialize(this.context, this.context.getMainLooper(), this::sendStateChange);
            if (channel == null) {
                logger.log(WARNING, "Cannot initialize Wi-Fi Direct");
            }

        }

        this.receiver = new WifiDirectBroadcastReceiver();
        registerWifiIntentReceiver();
        wifiP2pManager.requestDeviceInfo(channel, this::processDeviceInfo);

    }

    public void unregisterWifiIntentReceiver() {
        context.unregisterReceiver(receiver);
    }

    public void registerWifiIntentReceiver() {
        context.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED);
    }

    public WifiDirectStatus getStatus() {
        return status;
    }

    @SuppressLint("MissingPermission")
    public CompletableActionListener createGroup() {
        var cal = new CompletableActionListener();
        this.wifiP2pManager.createGroup(this.channel, cal);
        return cal;
    }

    /**
     * Leave the current WifiDirect Group this device is connected to
     *
     * @return Future containing true if successful false if not
     */
    public CompletableFuture<OptionalInt> removeGroup() {
        var cal = new CompletableActionListener();
        this.wifiP2pManager.removeGroup(this.channel, cal);
        return cal;
    }

    void processDeviceInfo(WifiP2pDevice wifiP2pDevice) {
        if (wifiP2pDevice != null) {
            if (!wifiP2pDevice.deviceName.equals(deviceName)) {
                this.deviceName = wifiP2pDevice.deviceName;
                // device name changed, so redo the group
                if (wifiP2pDevice.status == WifiP2pDevice.CONNECTED) removeGroup();
                if (deviceName.startsWith("ddd_")) createGroup();
                sendDeviceNameChange();
            }
            var infoChanged = false;
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
                sendStateChange();
            }
        }
    }

    public enum DDDWifiServerEventType {
        DDDWIFISERVER_NETWORKINFO_CHANGED, DDDWIFISERVER_DEVICENAME_CHANGED, DDDWIFISERVER_MESSAGE
    }

    public static class DDDWifiServerEvent {
        public final DDDWifiServerEventType type;
        public final String data;

        public DDDWifiServerEvent(DDDWifiServerEventType type, String data) {
            this.type = type;
            this.data = data;
        }
    }

    public static class DDDWifiNetworkInfo {
        public final String ssid;
        public final String password;
        public final InetAddress inetAddress;
        public final List<String> clientList;

        public DDDWifiNetworkInfo(String ssid, String password, InetAddress inetAddress, List<String> clientList) {
            this.ssid = ssid;
            this.password = password;
            this.inetAddress = inetAddress;
            this.clientList = clientList;
        }
    }

    public static class CompletableActionListener extends CompletableFuture<OptionalInt>
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
                        var state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                        if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            logger.log(INFO, "WifiDirect enabled");
                            wifiDirectEnabled = true;
                            wifiP2pManager.requestDeviceInfo(channel, DDDWifiServer.this::processDeviceInfo);
                        } else {
                            logger.log(INFO, "WifiDirect not enabled");
                            wifiDirectEnabled = false;
                        }
                        sendStateChange();
                    }
                    case WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        //         Broadcast intent action indicating that the state of Wi-Fi p2p
                        //         connectivity has changed.
                        //         EXTRA_WIFI_P2P_INFO provides the p2p connection info in the form of a
                        //         WifiP2pInfo object.
                        //         EXTRA_WIFI_P2P_GROUP provides the details of the group and
                        //         may contain a null
                        var conInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo.class);
                        DDDWifiServer.this.wifiGroup =
                                intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP, WifiP2pGroup.class);

                        DDDWifiServiceEvents.sendEvent(new DDDWifiServerEvent(DDDWifiServerEventType.DDDWIFISERVER_MESSAGE,
                                                                              "Group info: " +
                                                                                      (DDDWifiServer.this.wifiGroup ==
                                                                                               null ?
                                                                                       "N/A" :
                                                                                       DDDWifiServer.this.wifiGroup.getClientList()
                                                                                               .stream()
                                                                                               .map(d -> d.deviceName)
                                                                                               .collect(Collectors.joining(
                                                                                                       ", ")))));
                        sendStateChange();
                    }
                    case WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> processDeviceInfo(intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                            WifiP2pDevice.class));
                }
            }
        }
    }
}