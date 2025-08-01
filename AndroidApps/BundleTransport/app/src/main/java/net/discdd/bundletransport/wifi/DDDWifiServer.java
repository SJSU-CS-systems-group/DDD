package net.discdd.bundletransport.wifi;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import androidx.core.app.ActivityCompat;
import net.discdd.bundletransport.BundleTransportService;
import net.discdd.bundletransport.R;
import net.discdd.bundletransport.service.DDDWifiServiceEvents;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

public class DDDWifiServer {
    private static final Logger logger = Logger.getLogger(DDDWifiServer.class.getName());
    private final IntentFilter intentFilter = new IntentFilter();
    private final BundleTransportService bts;
    private WifiP2pManager wifiP2pManager;
    private WifiP2pManager.Channel channel;
    private WifiP2pGroup wifiGroup;
    private String deviceName;
    private WifiDirectBroadcastReceiver receiver;

    private WifiDirectStatus status = WifiDirectStatus.UNDEFINED;

    @SuppressLint("MissingPermission")
    public void wifiPermissionGranted() {
        if (channel == null) {
            initialize();
        } else if (wifiGroup == null) {
            // we need to reregister things with the new permission
            unregisterWifiIntentReceiver();
            registerWifiIntentReceiver();
            wifiP2pManager.requestDeviceInfo(channel, this::processDeviceInfo);
        }
    }

    public enum WifiDirectStatus {FAILED, INVITED, AVAILABLE, UNAVAILABLE, UNDEFINED, CONNECTED}

    public DDDWifiServer(BundleTransportService bundleTransportService) {
        intentFilter.addAction(WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        this.bts = bundleTransportService;
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

    public void shutdown() {
        unregisterWifiIntentReceiver();
        if (channel != null) channel.close();
    }

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

    @SuppressLint("MissingPermission")
    public void initialize() {
        this.wifiP2pManager = (WifiP2pManager) this.bts.getSystemService(Context.WIFI_P2P_SERVICE);
        if (wifiP2pManager == null) {
            logger.log(SEVERE, "Cannot get Wi-Fi system service");
        } else {
            this.channel = this.wifiP2pManager.initialize(this.bts, this.bts.getMainLooper(), this::sendStateChange);
            if (channel == null) {
                logger.log(WARNING, "Cannot initialize Wi-Fi Direct");
            }

        }

        this.receiver = new WifiDirectBroadcastReceiver();
        if (hasPermission()) {
            registerWifiIntentReceiver();
            wifiP2pManager.requestDeviceInfo(channel, this::processDeviceInfo);
        }
    }

    AtomicBoolean intentRegistered = new AtomicBoolean(false);

    public void unregisterWifiIntentReceiver() {
        if (!intentRegistered.getAndSet(false)) return;
        bts.unregisterReceiver(receiver);
    }

    public void registerWifiIntentReceiver() {
        if (intentRegistered.getAndSet(true)) return;
        bts.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED);
    }

    public WifiDirectStatus getStatus() {
        return status;
    }

    private boolean hasPermission() {
        return ActivityCompat.checkSelfPermission(this.bts, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                        PackageManager.PERMISSION_GRANTED;
    }

    /**
     * represents the state of group creation.
     * if null, no group has been created, otherwise it is the name
     * of the group being created.
     * <p>
     * NOTE: there is some weirdness: the SSID doesn't seem to change
     * if the device name changes...
     */
    AtomicReference<String> createdGroupName = new AtomicReference<>(null);

    @SuppressLint("MissingPermission")
    public void createGroup() {
        if (deviceName == null || !deviceName.startsWith("ddd_")) return;
        if (!hasPermission()) {
            bts.logWifi(SEVERE, R.string.wifi_direct_no_permission);
            return;
        }
        var groupName = "DIRECT-" + deviceName;
        var oldGroupName = createdGroupName.get();
        if (groupName.equals(oldGroupName) || !createdGroupName.compareAndSet(oldGroupName, groupName)) {
            // Create a group only if we haven't created one yet
            return;
        }
        var cal = new CompletableActionListener();
        this.wifiP2pManager.createGroup(this.channel, cal);
        cal.handle((optRc, ex) -> {
            if (ex != null) {
                bts.logWifi(SEVERE, ex, R.string.wifi_direct_create_group_failed_e, ex.getMessage());
                createdGroupName.set(null);
            } else if (optRc.isPresent()) {
                bts.logWifi(SEVERE, R.string.wifi_direct_create_group_failed_d, optRc.getAsInt());
                createdGroupName.set(null);
            } else {
                bts.logWifi(INFO, R.string.wifi_direct_create_group_success);
            }
            return null;
        });
    }

    /**
     * Leave the current WifiDirect Group this device is connected to
     *
     * @return Future containing true if successful false if not
     */
    public CompletableFuture<OptionalInt> removeGroup() {
        var cal = new CompletableActionListener();
        if (wifiGroup != null) {this.wifiP2pManager.removeGroup(this.channel, cal);} else {
            cal.complete(OptionalInt.empty());
        }
        return cal;
    }

    void processDeviceInfo(WifiP2pDevice wifiP2pDevice) {
        if (wifiP2pDevice != null) {
            if (!wifiP2pDevice.deviceName.equals(deviceName)) {
                this.deviceName = wifiP2pDevice.deviceName;
                // device name changed, so redo the group
                if (wifiP2pDevice.status == WifiP2pDevice.CONNECTED) removeGroup();
                sendDeviceNameChange();
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
                sendStateChange();
            }
        }
    }

    public enum DDDWifiServerEventType {
        DDDWIFISERVER_NETWORKINFO_CHANGED, DDDWIFISERVER_DEVICENAME_CHANGED
    }

    public static class DDDWifiServerEvent {
        public final DDDWifiServerEventType type;
        public final String data;

        public DDDWifiServerEvent(DDDWifiServerEventType type, String data) {
            this.type = type;
            this.data = data;
        }

        public String toString() {
            return type + (data == null ? "" : ": " + data);
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
         */
        @SuppressLint("MissingPermission")
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
                            if (hasPermission()) {
                                wifiP2pManager.requestDeviceInfo(channel, DDDWifiServer.this::processDeviceInfo);
                                createGroup();
                            }
                        } else {
                            // WifiDirect is not enabled, so we clear createdGroupName so that the group will
                            // be recreated when WifiDirect is enabled again
                            createdGroupName.set(null);
                            logger.log(INFO, "WifiDirect not enabled");
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
                        var oldGroup = DDDWifiServer.this.wifiGroup;
                        var newGroup =
                                intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP, WifiP2pGroup.class);

                        if (newGroup == null) {
                            if (oldGroup != null) {
                                bts.logWifi(INFO, R.string.left_group_s, oldGroup.getNetworkName());
                            }
                        } else {
                            if (oldGroup == null || !oldGroup.getNetworkName().equals(newGroup.getNetworkName())) {
                                bts.logWifi(INFO, R.string.new_group_s, newGroup.getNetworkName());
                            }
                            var newClientList = newGroup.getClientList()
                                    .stream()
                                    .map(w -> w.deviceName)
                                    .collect(Collectors.toSet());
                            var origNewClientList = new HashSet<>(newClientList);
                            var oldClientList = oldGroup == null ?
                                                Collections.<String>emptySet() :
                                                oldGroup.getClientList()
                                                        .stream()
                                                        .map(w -> w.deviceName)
                                                        .collect(Collectors.toSet());
                            newClientList.removeAll(oldClientList);
                            oldClientList.removeAll(origNewClientList);
                            if (!newClientList.isEmpty()) {
                                bts.logWifi(INFO, R.string.new_clients_s, String.join(", ", newClientList));
                            }
                            if (!oldClientList.isEmpty()) {
                                bts.logWifi(INFO, R.string.lost_clients_s, String.join(", ", oldClientList));
                            }
                        }
                        DDDWifiServer.this.wifiGroup = newGroup;
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