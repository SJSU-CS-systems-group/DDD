package net.discdd.bundletransport.wifi;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import net.discdd.bundletransport.BundleTransportService;
import net.discdd.bundletransport.R;
import net.discdd.bundletransport.service.DDDWifiServiceEvents;
import net.discdd.grpc.GetRecencyBlobResponse;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION;
import static java.lang.String.format;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

public class DDDWifiServer {
    private static final Logger logger = Logger.getLogger(DDDWifiServer.class.getName());
    public static final String DDD_NETWORK_NAME = "DIRECT-ddd_";
    private final IntentFilter intentFilter = new IntentFilter();
    private final BundleTransportService bts;
    private WifiP2pManager wifiP2pManager;
    private WifiP2pManager.Channel channel;
    private WifiP2pGroup wifiGroup;
    private WifiDirectBroadcastReceiver receiver;
    private String deviceName;
    private GetRecencyBlobResponse lastRecencyBlob;
    private WifiP2pDnsSdServiceInfo serviceInfo;
    private Map<String, String> txt;


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
        lastRecencyBlob = GetRecencyBlobResponse.getDefaultInstance();
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
        return new DDDWifiNetworkInfo(wifiGroup.getNetworkName(),
                                      wifiGroup.getPassphrase(),
                                      inetAddress,
                                      clientList,
                                      deviceName);
    }

    public void shutdown() {
        unregisterWifiIntentReceiver();
        if (channel != null) channel.close();
    }

    public void sendStateChange() {
        DDDWifiServiceEvents.sendEvent(new DDDWifiServerEvent(DDDWifiServerEventType.DDDWIFISERVER_NETWORKINFO_CHANGED,
                                                              null));
    }

    public void sendWifiStatusChange() {
        DDDWifiServiceEvents.sendEvent(new DDDWifiServerEvent(DDDWifiServerEventType.DDDWIFISERVER_WIFI_STATUS_CHANGED,
                                                              null));
    }

    @SuppressLint("MissingPermission")
    public void initialize() {
        this.receiver = new WifiDirectBroadcastReceiver();
        registerWifiIntentReceiver();

        bts.logWifi(INFO, R.string.initializing_wifidirectmanager);
        this.wifiP2pManager = (WifiP2pManager) this.bts.getSystemService(Context.WIFI_P2P_SERVICE);
        if (wifiP2pManager == null) {
            logger.log(SEVERE, "Cannot get Wi-Fi system service");
        } else {
            this.channel = this.wifiP2pManager.initialize(this.bts, this.bts.getMainLooper(), this::sendStateChange);
            if (channel == null) {
                logger.log(WARNING, "Cannot initialize Wi-Fi Direct");
            }
        }

        if (hasPermission()) {
            wifiP2pManager.requestDeviceInfo(channel, this::processDeviceInfo);
            bts.logWifi(INFO, R.string.wifi_direct_initialized);
            // make sure android 13 devices create group because their actions
            // might not be received by WifiDirectBroadcastReceiver
            createGroup();
        } else {
            bts.logWifi(SEVERE, R.string.no_permission_for_wi_fi_direct);
        }
        sendStateChange();
        sendWifiStatusChange();
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

    @SuppressLint("MissingPermission")
    public void createGroup() {
        if (wifiGroup != null && wifiGroup.getNetworkName().startsWith(DDD_NETWORK_NAME + bts.transportId)) return;
        if (!hasPermission()) {
            bts.logWifi(SEVERE, R.string.wifi_direct_no_permission);
            return;
        }
        if (wifiGroup != null) {
            removeGroup().thenAccept(x -> {innerCreateGroupRunnable();});
        } else {
            innerCreateGroupRunnable();
        }
    }

    @SuppressLint("MissingPermission")
    private CompletableActionListener innerCreateGroupRunnable() {
        var cal = new CompletableActionListener();
        WifiP2pConfig config = new WifiP2pConfig.Builder().setNetworkName(DDD_NETWORK_NAME + bts.transportId)
                .setPassphrase("ConnectToMe")
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                .enablePersistentMode(true)
                .build();
        this.wifiP2pManager.createGroup(this.channel, config, cal);
        cal.handle((optRc, ex) -> {
            if (ex != null) {
                bts.logWifi(SEVERE, ex, R.string.wifi_direct_create_group_failed_e, ex.getMessage());
            } else if (optRc.isPresent()) {
                bts.logWifi(INFO, R.string.wifi_direct_create_group_failed_d, optRc.getAsInt());
            } else {
                bts.logWifi(INFO, R.string.wifi_direct_create_group_success);
            }
            // make sure android 14/15 devices get existing groups if createGroup fails
            getGroup();
            return null;
        });

        try {
            logger.log(INFO, format("ServerKey: %s, Signature: %s, Nonce: %s, Time: %s", Base64.getEncoder().encodeToString((lastRecencyBlob.getServerPublicKey().toByteArray())),
                    Base64.getEncoder().encodeToString((lastRecencyBlob.getRecencyBlobSignature().toByteArray())),
                                    lastRecencyBlob.getRecencyBlob().getNonce(),
                                    lastRecencyBlob.getRecencyBlob().getBlobTimestamp()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        txt = new HashMap<>();
        txt.put("ddd", bts.getBundleServerURL());
        txt.put("transportId", bts.transportId);
        txt.put("recencyBlob", Base64.getEncoder().encodeToString(lastRecencyBlob.toByteArray()));

        // DNS-SD service info
        serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
                /* instanceName = */ "ddd",
                /* serviceType  = */ "_ddd._tcp",
                /* txtRecord    = */ txt);

        this.wifiP2pManager.addLocalService(channel, serviceInfo, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
            }

            @Override
            public void onFailure(int reason) {
                logger.log(SEVERE, "Failed to add local service: " + reason);
            }
        });
        return cal;
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
        this.wifiP2pManager.clearLocalServices(channel, null);
        return cal;
    }

    @SuppressLint("MissingPermission")
    public void getGroup() {
        wifiP2pManager.requestGroupInfo(channel, g -> {
            wifiGroup = g;
            sendStateChange();
            sendWifiStatusChange();
            bts.logWifi(INFO, R.string.wifi_direct_get_group_success);
        });
    }

    void processDeviceInfo(WifiP2pDevice wifiP2pDevice) {
        if (wifiP2pDevice != null) {
            this.deviceName = wifiP2pDevice.deviceName;
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

    @SuppressLint("MissingPermission")
    public void updateRecencyBlob(GetRecencyBlobResponse recencyBlob) {
        this.lastRecencyBlob = recencyBlob;
        int i = Base64.getEncoder().encodeToString(lastRecencyBlob.toByteArray()).length();
        this.wifiP2pManager.removeLocalService(channel, serviceInfo, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {}

            @Override
            public void onFailure(int reason) {}
        });
        txt.put("recencyBlob", Base64.getEncoder().encodeToString(lastRecencyBlob.toByteArray()));
        serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
                /* instanceName = */ "ddd",
                /* serviceType  = */ "_ddd._tcp",
                /* txtRecord    = */ txt);
        this.wifiP2pManager.addLocalService(channel, serviceInfo, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {}

            @Override
            public void onFailure(int reason) {}
        });
    }

    public enum DDDWifiServerEventType {
        DDDWIFISERVER_NETWORKINFO_CHANGED, DDDWIFISERVER_WIFI_STATUS_CHANGED
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
        public final String deviceName;

        public DDDWifiNetworkInfo(String ssid,
                                  String password,
                                  InetAddress inetAddress,
                                  List<String> clientList,
                                  String deviceName) {
            this.ssid = ssid;
            this.password = password;
            this.inetAddress = inetAddress;
            this.clientList = clientList;
            this.deviceName = deviceName;
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
            if (action != null && wifiP2pManager != null) {
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
                        sendWifiStatusChange();
                    }
                    case WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> processDeviceInfo(intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                            WifiP2pDevice.class));
                }
            }
        }
    }
}