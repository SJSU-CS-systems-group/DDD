package net.discdd.bundleclient;

import static net.discdd.wifidirect.WifiDirectManager.WifiDirectEventType.WIFI_DIRECT_MANAGER_SERVICE_DISCOVERED;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.discdd.client.bundlesecurity.BundleSecurity;
import net.discdd.client.bundletransmission.BundleTransmission;
import net.discdd.client.bundletransmission.BundleTransmission.BundleExchangeCounts;
import net.discdd.model.ADU;
import net.discdd.pathutils.ClientPaths;
import net.discdd.wifidirect.DiscoveredService;
import net.discdd.wifidirect.WifiDirectManager;
import net.discdd.wifidirect.WifiDirectStateListener;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public class BundleClientWifiDirectService extends Service implements WifiDirectStateListener {
    public static final String NET_DISCDD_BUNDLECLIENT_LOG_ACTION = "net.discdd.bundleclient.CLIENT_LOG";
    public static final String NET_DISCDD_BUNDLECLIENT_WIFI_EVENT_ACTION = "net.discdd.bundleclient.WIFI_EVENT";
    public static final String NET_DISCDD_BUNDLECLIENT_SETTINGS = "net.discdd.bundleclient";
    public static final int REEXCHANGE_TIME_PERIOD_MS = 2 * 60 * 1000;
    public static final String WIFI_DIRECT_EVENT_EXTRA = "wifiDirectEvent";
    public static final String BUNDLE_CLIENT_WIFI_EVENT_EXTRA = "BundleClientWifiEvent";
    public static final String NET_DISCDD_BUNDLECLIENT_SETTING_BACKGROUND_EXCHANGE = "background_exchange";
    public static final String NET_DISCDD_BUNDLECLIENT_DEVICEADDRESS_EXTRA = "deviceAddress";
    private static final Logger logger = Logger.getLogger(BundleClientWifiDirectService.class.getName());
    private static final BundleExchangeCounts ZERO_BUNDLE_EXCHANGE_COUNTS = new BundleExchangeCounts(0, 0);
    private static SharedPreferences preferences;
    final AtomicReference<CompletableFuture<Void>> connectionWaiter = new AtomicReference<>();
    private final IBinder binder = new BundleClientWifiDirectServiceBinder();
    private final ScheduledExecutorService periodicExecutor = Executors.newScheduledThreadPool(1);
    PeriodicRunnable periodicRunnable = new PeriodicRunnable();
    private WifiDirectManager wifiDirectManager;
    private BundleTransmission bundleTransmission;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        preferences = getSharedPreferences(NET_DISCDD_BUNDLECLIENT_SETTINGS, MODE_PRIVATE);
        preferences.registerOnSharedPreferenceChangeListener((sharedPreferences, key) -> {
            if (NET_DISCDD_BUNDLECLIENT_SETTING_BACKGROUND_EXCHANGE.equals(key)) {
                processBackgroundExchangeSetting();
            }
        });
        startForeground();
        return START_STICKY;
    }

    private void processBackgroundExchangeSetting() {
        var backgroundExchange = preferences.getBoolean(NET_DISCDD_BUNDLECLIENT_SETTING_BACKGROUND_EXCHANGE, false);
        if (backgroundExchange) {
            periodicRunnable.schedule();
        } else {
            periodicRunnable.cancel();
        }
        logger.info("Client background exchange changed to " + backgroundExchange);
    }

    private void startForeground() {
        try {
            NotificationChannel channel =
                    new NotificationChannel("DDD-Client", "DDD Bundle Client", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("DDD Client Service");

            var notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);

            Notification notification =
                    new NotificationCompat.Builder(this, "DDD-Client").setContentTitle("DDD Bundle Client").build();
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            ServiceCompat.startForeground(this, 1, notification, type);
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to start foreground service", e);
        }

        wifiDirectManager = new WifiDirectManager(this, this);
        wifiDirectManager.initialize();
        try {
            ClientPaths clientPaths = new ClientPaths(getApplicationContext().getDataDir().toPath());

            //Application context
            var resources = getApplicationContext().getResources();
            try (InputStream inServerIdentity = resources.openRawResource(
                    net.discdd.android_core.R.raw.server_identity); InputStream inServerSignedPre =
                    resources.openRawResource(
                    net.discdd.android_core.R.raw.server_signed_pre); InputStream inServerRatchet =
                    resources.openRawResource(
                    net.discdd.android_core.R.raw.server_ratchet)) {
                BundleSecurity.initializeKeyPaths(clientPaths, inServerIdentity, inServerSignedPre, inServerRatchet);
            } catch (IOException e) {
                logger.log(SEVERE, "[SEC]: Failed to initialize Server Keys", e);
            }

            bundleTransmission = new BundleTransmission(clientPaths, this::processIncomingADU);
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to initialize BundleTransmission", e);
        }
    }

    private void processIncomingADU(ADU adu) {
        //notify app that someone sent data for the app
        Intent intent = new Intent("android.intent.dtn.DATA_RECEIVED");
        intent.setPackage(adu.getAppId());
        intent.setType("text/plain");
        getApplicationContext().sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        if (wifiDirectManager != null) wifiDirectManager.shutdown();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void addConnectionWaiter(CompletableFuture<Void> connectedFuture) {
        logger.log(INFO, "Adding connection waiter" + connectedFuture);
        var oldFuture = connectionWaiter.getAndSet(connectedFuture);
        if (oldFuture != null && !oldFuture.isDone()) {
            oldFuture.completeExceptionally(new TimeoutException("Connection timed out"));
        }
    }

//    private void completeConnectionWaiter(WifiP2pGroup groupInfo) {
//        var future = connectionWaiter.getAndSet(null);
//        logger.log(INFO, "Completing connection waiter" + future);
//
//        if (future != null && !future.isDone()) {
//            future.complete(groupInfo);
//        }
//    }

    @Override
    public void onReceiveAction(WifiDirectManager.WifiDirectEvent action) {
        switch (action.type()) {
            case WIFI_DIRECT_MANAGER_INITIALIZED -> {
                logger.info("WifiDirectManager initialized, searching for services");
                discoverServices();
            }
            case WIFI_DIRECT_MANAGER_SERVICE_DISCOVERED -> {
                logger.info("New transport service discovered.");
                List<DiscoveredService> services = wifiDirectManager.getDiscoveredServices();

                services.stream()
                        .filter(service -> service.getRegistrationType().equals("ddd_transport_service"))
                        .forEach(service -> {
                            logger.info("Discovered service details - Device: " + service.getDevice().deviceName +
                                                ", MAC Address: " + service.getDeviceAddress() +
                                                ", Port: " + service.getPort());
                            String deviceName = String.valueOf(service.getDevice());
                            String deviceAddress = String.valueOf(service.getDeviceAddress());
                            int port = service.getPort();

                            bundleTransmission.processDiscoveredService(deviceName, deviceAddress, port);
                        });

                // Expire services that haven’t been seen for a minute
                long expirationTime = System.currentTimeMillis() - 60 * 1000;
                bundleTransmission.expireNotSeenServices(expirationTime);
                if (periodicExecutor == null) processBackgroundExchangeSetting();
            }
        }
        broadcastWifiEvent(action);
    }

    private void exchangeWithTransports() {
        var recentTransports = bundleTransmission.getRecentTransports();

        for (var transport : recentTransports) {
            // Match transport with discovered service by device name
            wifiDirectManager.getDiscoveredServices().stream()
                    .filter(service -> service.getDeviceName().equals(transport.getDeviceName()))
                    .findFirst()
                    .ifPresent(service -> {
                        String deviceAddress = String.valueOf(service.getDeviceAddress());
                        int port = service.getPort();

                        WifiP2pDevice transportDevice = service.getDevice();
                        var counts = exchangeWith(deviceAddress, port, transport.getDeviceName(), transportDevice);
                        logger.info(
                                String.format(getString(R.string.exchanged_d_bundles_to_and_d_bundles_from_s),
                                              counts.bundlesSent(), counts.bundlesReceived(),
                                              transport.getDeviceName()));
                    });
        }
    }

    private BundleExchangeCounts exchangeWith(String deviceAddress, int port, String transportName, WifiP2pDevice transport) {
        // transport.deviceAddress is the address of the client
        broadcastBundleClientWifiEvent(BundleClientWifiDirectEventType.WIFI_DIRECT_CLIENT_EXCHANGE_STARTED, transport.deviceAddress);;
        try {
            connectTo(transport).get(10, TimeUnit.SECONDS);
            return bundleTransmission.doExchangeWithTransport(transport.deviceAddress, transport.deviceName,
                                                              deviceAddress, port);
        } catch (Throwable e) {
            logger.log(WARNING, "Failed to connect to " + transportName, e);
        } finally {
            wifiDirectManager.disconnect();
            broadcastBundleClientWifiEvent(BundleClientWifiDirectEventType.WIFI_DIRECT_CLIENT_EXCHANGE_FINISHED,
                                           transport.deviceAddress);
        }
        return new BundleExchangeCounts(0, 0);
    }

    private CompletableFuture<Void> connectTo(WifiP2pDevice transport) {
        var connectedFuture = new CompletableFuture<Void>();
        addConnectionWaiter(connectedFuture);
        // NOTE: we don't return the future from connect because that future only triggers
        //       at the data link layer.
        wifiDirectManager.connect(transport);
        return connectedFuture;
    }

    private void broadcastWifiEvent(WifiDirectManager.WifiDirectEvent event) {
        //var intent = new Intent(getApplicationContext(), TransportWifiDirectFragment.class);
        var intent = new Intent(NET_DISCDD_BUNDLECLIENT_WIFI_EVENT_ACTION);
        intent.putExtra(WIFI_DIRECT_EVENT_EXTRA, event.type());
        intent.putExtra("message", event.message());
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    private void broadcastBundleClientWifiEvent(BundleClientWifiDirectEventType event, String deviceAddress) {
        //var intent = new Intent(getApplicationContext(), TransportWifiDirectFragment.class);
        var intent = new Intent(NET_DISCDD_BUNDLECLIENT_WIFI_EVENT_ACTION);
        intent.putExtra(BUNDLE_CLIENT_WIFI_EVENT_EXTRA, event);
        intent.putExtra(NET_DISCDD_BUNDLECLIENT_DEVICEADDRESS_EXTRA, deviceAddress);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    public String getClientId() {
        return bundleTransmission.getBundleSecurity().getClientSecurity().getClientID();
    }

//    public void discoverPeers() {
//        wifiDirectManager.discoverPeers();
//    }

//    public void discoverServices() {
//        wifiDirectManager.discoverServices();
//    }


    public void discoverServices() {
        WifiP2pManager manager = wifiDirectManager.getManager();
        WifiP2pManager.Channel channel = wifiDirectManager.getChannel();

        if (manager == null || channel == null) {
            logger.log(SEVERE, "WiFi P2P Manager or Channel is null, aborting discovery");
            return;
        }

        Map<String, String> buddies = new HashMap<>();

        WifiP2pManager.DnsSdTxtRecordListener txtListener =
                (fullDomain, record, device) -> {
                    logger.log(INFO, "DnsSd TXT record available - " + record.toString());
                    // Store the device name from the record
                    if (record.containsKey("device_name")) {
                        buddies.put(device.deviceAddress, record.get("device_name"));
                    }
                };

        // Updates the list of available services
        WifiP2pManager.DnsSdServiceResponseListener servListener =
                (instanceName, registrationType, device) -> {
                    logger.log(INFO, "Current buddies: " + buddies);
                    if (!registrationType.contains("_dddtransport._tcp")) {
                        logger.log(INFO, "Ignoring service with non-matching type: " + registrationType);
                        return;
                    }

                    device.deviceName = buddies.containsKey(device.deviceAddress) ?
                            buddies.get(device.deviceAddress) : device.deviceName;

                    logger.log(INFO, "DnsSd service available - Instance: " + instanceName);
                    logger.log(INFO, "Device: " + device.deviceName + " - Type: " + registrationType);
                    logger.log(INFO, "Device: " + device.deviceName + " - " +
                            device.deviceAddress);

                    logger.log(INFO, "Discovered service: " + device.deviceName + " (" + device.deviceAddress + ")");

                    List<DiscoveredService> discoveredServices = wifiDirectManager.getDiscoveredServices();
                    synchronized (discoveredServices) {
                        discoveredServices.add(new DiscoveredService(device, device.deviceAddress, 7777));
                    }
                };
        wifiDirectManager.notifyActionToListeners(WIFI_DIRECT_MANAGER_SERVICE_DISCOVERED);

        logger.log(INFO, "Setting up service discovery...");
        manager.setDnsSdResponseListeners(channel, servListener, txtListener);
        logger.log(INFO, "Service discovery listener set.");

        Context context = getApplicationContext();
        if (context instanceof BundleClientActivity) {
            BundleClientActivity activity = (BundleClientActivity) context;
            BundleClientWifiDirectFragment fragment =
                    (BundleClientWifiDirectFragment) activity.getSupportFragmentManager()
                            .findFragmentById(R.id.services_list);

            if (fragment != null) {
                fragment.updateConnectedDevices();
            }
        }

        // Create and add service request
        WifiP2pDnsSdServiceRequest
                serviceRequest = WifiP2pDnsSdServiceRequest.newInstance("_dddtransport._tcp");
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

        if (ActivityCompat.checkSelfPermission(this,
                                               android.Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(this,
                                                                                        android.Manifest.permission.NEARBY_WIFI_DEVICES) !=
                PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        manager.discoverServices(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                logger.log(INFO, "Service discovery successful");
            }

            @Override
            public void onFailure(int code) {
                logger.log(SEVERE, "Service discovery failed with code " + code);
                new Handler(Looper.getMainLooper()).postDelayed(() -> discoverServices(), 5000);
            }
        });
    }

    public CompletableFuture<BundleExchangeCounts> initiateExchange(String deviceAddress) {
        var completableFuture = new CompletableFuture<BundleExchangeCounts>();
        DiscoveredService discoveredService = wifiDirectManager.getDiscoveredServices()
                .stream()
                .filter(service -> service.getDevice().deviceAddress.equals(deviceAddress))
                .findFirst()
                .orElse(null);
        if (discoveredService == null) {
            completableFuture.complete(ZERO_BUNDLE_EXCHANGE_COUNTS);
            return completableFuture;
        }

        // Extract necessary device information
        String transportAddress = discoveredService.getDeviceAddress();
        int transportPort = discoveredService.getPort();
        String transportName = discoveredService.getDeviceName();
        WifiP2pDevice transportDevice = discoveredService.getDevice();
        // we want to use the executor to make sure that only one exchange is going on at a time
        periodicExecutor.submit(() -> {
            try {
                var bundleExchangeCounts = exchangeWith(transportAddress, transportPort, transportName, transportDevice);
                completableFuture.complete(bundleExchangeCounts);
            } catch (Exception e) {
                logger.log(WARNING, "Failed to initiate exchange with " + transportName, e);
                completableFuture.complete(ZERO_BUNDLE_EXCHANGE_COUNTS);
            }
        });
        return completableFuture;
    }

    public CompletableFuture<BundleExchangeCounts> initiateServerExchange() {
        var completableFuture = new CompletableFuture<BundleExchangeCounts>();
        periodicExecutor.submit(() -> {
            try {
                var serverAddress = preferences.getString("domain", "");
                var port = preferences.getInt("port", 0);
                if (serverAddress.isEmpty() || port == 0) {
                    completableFuture.complete(ZERO_BUNDLE_EXCHANGE_COUNTS);
                    return;
                }
                var bundleExchangeCounts =
                        bundleTransmission.doExchangeWithTransport("XX:XX:XX:XX:XX:XX", "BundleServer", serverAddress,
                                                                   port);
                logger.log(INFO, String.format(getString(R.string.exchanged_d_bundles_to_and_d_bundles_from_server),
                                               bundleExchangeCounts.bundlesSent(),
                                               bundleExchangeCounts.bundlesReceived()));
                completableFuture.complete(bundleExchangeCounts);
            } catch (Exception e) {
                logger.log(WARNING, "Failed to initiate exchange with server", e);
                completableFuture.complete(ZERO_BUNDLE_EXCHANGE_COUNTS);
            }
        });
        return completableFuture;
    }

    public BundleTransmission.RecentTransport[] getRecentTransports() {
        return bundleTransmission.getRecentTransports();
    }

//    public WifiP2pGroup getGroupInfo() {
//        return wifiDirectManager.getGroupInfo();
//    }

    public boolean isDiscoveryActive() {
        return wifiDirectManager.isDiscoveryActive();
    }

    public BundleTransmission.RecentTransport getService(String deviceAddress) {
        return Arrays.stream(bundleTransmission.getRecentTransports())
                .filter(rt -> deviceAddress.equals(rt.getDeviceAddress())).findFirst()
                .orElse(new BundleTransmission.RecentTransport(deviceAddress));
    }

    public enum BundleClientWifiDirectEventType {
        WIFI_DIRECT_CLIENT_EXCHANGE_STARTED, WIFI_DIRECT_CLIENT_EXCHANGE_FINISHED
    }

    private class PeriodicRunnable implements Runnable {
        private ScheduledFuture<?> scheduledFuture;

        synchronized public void schedule() {
            if (scheduledFuture == null) {
                scheduledFuture = periodicExecutor.scheduleWithFixedDelay(this, 0, REEXCHANGE_TIME_PERIOD_MS,
                                                                          TimeUnit.MILLISECONDS);
            }
        }

        synchronized public void cancel() {
            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
                scheduledFuture = null;
            }
        }

        @Override
        public void run() {
            BundleClientWifiDirectService.this.exchangeWithTransports();
        }
    }

    public class BundleClientWifiDirectServiceBinder extends Binder {
        BundleClientWifiDirectService getService() {return BundleClientWifiDirectService.this;}
    }

    public BundleTransmission getBundleTransmission() {
        return bundleTransmission;
    }
}
