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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private String deviceName;
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private WifiDirectBroadcastReceiver receiver;
    private WifiDirectStatus status = WifiDirectStatus.UNDEFINED;
    private final HashSet<DiscoveredService> discoveredServices = new HashSet<>();
    private boolean wifiDirectEnabled;
    private boolean discoveryActive;

    public WifiDirectManager(Context context, WifiDirectStateListener listener) {
        this.context = context;
        listeners.add(listener);
        intentFilter.addAction(WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        intentFilter.addAction(WIFI_P2P_DISCOVERY_CHANGED_ACTION);
    }

    public void initialize() {
        this.manager = (WifiP2pManager) this.context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            logger.log(INFO, "Cannot get Wi-Fi system service");
        } else {
            this.channel = this.manager.initialize(this.context, this.context.getMainLooper(),
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
    void processDeviceInfo(WifiP2pDevice wifiP2pDevice) {
        if (wifiP2pDevice != null) {
            var infoChanged = false;
            if (!wifiP2pDevice.deviceName.equals(deviceName)) {
                deviceName = wifiP2pDevice.deviceName;
                infoChanged = true;
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

    public WifiP2pManager.Channel getChannel() {
        return channel;
    }

    public boolean isDiscoveryActive() {
        return discoveryActive;
    }

    public void discoverServices() {
        Map<String, String> buddies = new HashMap<>();

        // Stores nearby devices' relevant information to be accessed later
        WifiP2pManager.DnsSdTxtRecordListener txtListener = (fullDomainName, record, device) -> {
            String deviceName = record.get("device_name");
            String ipAddress = record.get("ip_address");
            int port = Integer.parseInt(record.get("port"));

            if (deviceName != null) {
                buddies.put(device.deviceAddress, deviceName);
            }

            logger.log(INFO, "DnsSd TXT record available - " + record);
            logger.log(INFO, "Device: " + device.deviceAddress + ", IP: " + ipAddress + ", Port: " + port);

            try {
                synchronized (discoveredServices) {
                    // Avoid adding duplicate services
                    if (discoveredServices.stream().noneMatch(service -> service.getDevice().deviceAddress.equals(device.deviceAddress))) {
                        discoveredServices.add(new DiscoveredService(device, ipAddress, port));
                    }
                }
            } catch (NumberFormatException e) {
                logger.log(SEVERE, "Invalid port format: " + port, e);
            }
        };

        // Updates the list of available services
        WifiP2pManager.DnsSdServiceResponseListener servListener = new WifiP2pManager.DnsSdServiceResponseListener() {
            @Override
            public void onDnsSdServiceAvailable(String instanceName, String registrationType,
                                                WifiP2pDevice device) {
                device.deviceName = buddies.containsKey(device.deviceAddress) ?
                        buddies.get(device.deviceAddress) : device.deviceName;

                logger.log(INFO, "DnsSd service available - Instance: " + instanceName);
                logger.log(INFO, "Device: " + device.deviceName + " - Type: " + registrationType);
                logger.log(INFO, "Device: " + device.deviceName + " - " +
                        device.deviceAddress);
            }
        };

        manager.setDnsSdResponseListeners(channel, servListener, txtListener);

        // Create and add service request
        WifiP2pDnsSdServiceRequest serviceRequest = WifiP2pDnsSdServiceRequest.newInstance();
        manager.addServiceRequest(channel, serviceRequest, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                logger.log(INFO, "Service request added successfully");
            }

            @Override
            public void onFailure(int reason) {
                logger.log(SEVERE, "Service request failed. Reason: " + reason);
            }
        });

        // Start service discovery
        manager.discoverServices(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                logger.log(INFO, "Service discovery successful");
            }

            @Override
            public void onFailure(int code) {
                logger.log(SEVERE, "Service discovery failed with code " + code);
            }
        });
    }

    /**
     * Discovers WifiDirect peers for this device.
     *
     * @return Completable Future true if discovery is successful false if not
     */

//    @SuppressLint("MissingPermission")
//    public CompletableFuture<OptionalInt> discoverPeers() {
//        var completableActionListener = new CompletableActionListener();
//        this.manager.discoverPeers(this.channel, completableActionListener);
//        return completableActionListener;
//    }

//    /**
//     * Get the GroupInfo of current WifiDirect devices connected to this group.
//     *
//     * @return WifiP2PGroup object containing list of connected devices.
//     */

//    public CompletableFuture<WifiP2pGroup> requestGroupInfo() {
//        CompletableFuture<WifiP2pGroup> cFuture = new CompletableFuture<>();
//        this.manager.requestGroupInfo(channel, gi -> {
//            this.groupInfo = gi;
//            this.manager.requestConnectionInfo(channel, ci -> cFuture.complete(gi));
//        });
//        return cFuture;
//    }

    public CompletableFuture<Void> connect(WifiP2pDevice device) {
        var completableFuture = new CompletableFuture<Void>();
        var config = new WifiP2pConfig();
        config.wps.setup = WpsInfo.PBC;
        config.deviceAddress = device.deviceAddress;

        this.manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                completableFuture.complete(null);
            }

            @Override
            public void onFailure(int reasonCode) {
                completableFuture.completeExceptionally(new RuntimeException("Wi-Fi Direct connection failed. Reason: " + reasonCode));
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

//    public HashSet<WifiP2pDevice> getPeerList() {return discoveredPeers;}

    public CompletableFuture<Boolean> disconnect() {
        CompletableFuture<Boolean> completableFuture = new CompletableFuture<>();
        manager.clearServiceRequests(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                completableFuture.complete(true);
                logger.log(INFO, "Service requests cleared successfully");
            }
            @Override
            public void onFailure(int reason) {
                completableFuture.complete(false);
                logger.warning("Failed to clear service requests with reason " + reason);
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
        manager.cancelConnect(channel, cal);
        manager.stopPeerDiscovery(channel, cal);
        manager.stopListening(channel, cal);
        unregisterWifiIntentReceiver();
        return cal;
    }

//    public WifiP2pGroup getGroupInfo() {
//        return groupInfo;
//    }


    public boolean getWifiDirectEnabled() {
        return wifiDirectEnabled;
    }

    public void addLocalService(WifiP2pManager.Channel channel, WifiP2pDnsSdServiceInfo serviceInfo, WifiP2pManager.ActionListener actionListener) {
        manager.addLocalService(channel, serviceInfo, actionListener);
    }

    public List<DiscoveredService> getDiscoveredServices() {
        synchronized (discoveredServices) {
            return new ArrayList<>(discoveredServices);
        }
    }

    public enum WifiDirectStatus {FAILED, INVITED, AVAILABLE, UNAVAILABLE, UNDEFINED, CONNECTED}

    public enum WifiDirectEventType {
        WIFI_DIRECT_MANAGER_INITIALIZED,
        WIFI_DIRECT_MANAGER_SERVICE_DISCOVERED,
        WIFI_DIRECT_MANAGER_CONNECTION_CHANGED,
        WIFI_DIRECT_MANAGER_DISCOVERY_CHANGED,
        WIFI_DIRECT_MANAGER_SERVICES_CHANGED,
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

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) switch (action) {
                case WIFI_P2P_STATE_CHANGED_ACTION -> {
                    // Broadcast intent action to indicate whether Wi-Fi p2p is enabled or disabled
                    // Check if WifiDirect on this device is turned on.
                    int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        logger.log(INFO, "WifiDirect enabled");
                        wifiDirectEnabled = true;
                        manager.requestDeviceInfo(channel, WifiDirectManager.this::processDeviceInfo);
                        manager.requestDiscoveryState(channel, state1 -> discoveryActive =
                                state1 == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED);
                    } else {
                        logger.log(INFO, "WifiDirect not enabled");
                        wifiDirectEnabled = false;
                    }
                    notifyActionToListeners(WIFI_DIRECT_MANAGER_INITIALIZED);
                }
                case WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    // Broadcast intent action indicating that the available service list has changed.
                    // This can be sent as a result of services being found, lost or updated.
                    try {
                        var newServiceList = intent.getParcelableExtra(EXTRA_P2P_DEVICE_LIST, WifiP2pDeviceList.class);
                        if (newServiceList != null) {
                            discoveredServices.clear(); // Clear the existing services
                            for (WifiP2pDevice device : newServiceList.getDeviceList()) {
                                DiscoveredService discoveredService = new DiscoveredService(device, device.deviceAddress, 7777);
                                discoveredServices.add(discoveredService);
                            }
                            notifyActionToListeners(WifiDirectEventType.WIFI_DIRECT_MANAGER_SERVICES_CHANGED);
                        }
                    } catch (SecurityException e) {
                        logger.log(SEVERE, "SecurityException in getServicesList", e);
                    }
                }
                case WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    notifyActionToListeners(WifiDirectEventType.WIFI_DIRECT_MANAGER_CONNECTION_CHANGED);
                }
                case WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> processDeviceInfo(
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice.class));
                case WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                    int discoveryState = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1);
                    discoveryActive = discoveryState == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED;
                    notifyActionToListeners(WifiDirectEventType.WIFI_DIRECT_MANAGER_DISCOVERY_CHANGED);
                }
            }
        }
    }
}

