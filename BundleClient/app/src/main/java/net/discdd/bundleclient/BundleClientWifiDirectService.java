package net.discdd.bundleclient;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.os.Binder;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.discdd.client.bundlesecurity.BundleSecurity;
import net.discdd.client.bundletransmission.BundleTransmission;
import net.discdd.client.bundletransmission.BundleTransmission.BundleExchangeCounts;
import net.discdd.model.ADU;
import net.discdd.pathutils.ClientPaths;
import net.discdd.wifidirect.WifiDirectManager;
import net.discdd.wifidirect.WifiDirectStateListener;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
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
    final AtomicReference<CompletableFuture<WifiP2pGroup>> connectionWaiter = new AtomicReference<>();
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

        wifiDirectManager = new WifiDirectManager(this, this, false);
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

            bundleTransmission =
                    new BundleTransmission(clientPaths, this::processIncomingADU);
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

    private void addConnectionWaiter(CompletableFuture<WifiP2pGroup> connectedFuture) {
        logger.log(INFO, "Adding connection waiter" + connectedFuture);
        var oldFuture = connectionWaiter.getAndSet(connectedFuture);
        if (oldFuture != null && !oldFuture.isDone()) {
            oldFuture.completeExceptionally(new TimeoutException("Connection timed out"));
        }
    }

    private void completeConnectionWaiter(WifiP2pGroup groupInfo) {
        var future = connectionWaiter.getAndSet(null);
        logger.log(INFO, "Completing connection waiter" + future);

        if (future != null && !future.isDone()) {
            future.complete(groupInfo);
        }
    }

    @Override
    public void onReceiveAction(WifiDirectManager.WifiDirectEvent action) {
        switch (action.type()) {
            case WIFI_DIRECT_MANAGER_INITIALIZED -> {
                logger.info("WifiDirectManager initialized");
                wifiDirectManager.discoverPeers();
            }
            case WIFI_DIRECT_MANAGER_PEERS_CHANGED -> {
                logger.info("WifiDirectManager peers changed");
                wifiDirectManager.getPeerList().stream().filter(peer -> peer.deviceName.startsWith("ddd_"))
                        .forEach(peer -> bundleTransmission.processDiscoveredPeer(peer.deviceAddress, peer.deviceName));
                // expire peers that haven't been seen for a minute
                long expirationTime = System.currentTimeMillis() - 60 * 1000;
                bundleTransmission.expireNotSeenPeers(expirationTime);
                if (periodicExecutor == null) processBackgroundExchangeSetting();
            }
            case WIFI_DIRECT_MANAGER_GROUP_INFO_CHANGED -> {
                var ownerAddress = wifiDirectManager.getGroupOwnerAddress();
                if (ownerAddress != null) {
                    completeConnectionWaiter(wifiDirectManager.getGroupInfo());
                }
            }
        }
        broadcastWifiEvent(action);
    }

    private void exchangeWithTransports() {
        var recentTransports = bundleTransmission.getRecentTransports();
        for (var transport : recentTransports) {
            // map the transport deviceAddress to WifiP2pDevice in the peers list
            // and call exchangeWith if mapping exists
            wifiDirectManager.getPeerList().stream()
                    .filter(peer -> peer.deviceAddress.equals(transport.getDeviceAddress())).findFirst()
                    .map(this::exchangeWith).ifPresent(bc -> {
                        logger.info(
                                String.format(getString(R.string.exchanged_d_bundles_to_and_d_bundles_from_s),
                                              bc.bundlesSent(),
                                              bc.bundlesReceived(), transport.getDeviceName()));
                    });
        }
    }

    private BundleExchangeCounts exchangeWith(WifiP2pDevice device) {
        broadcastBundleClientWifiEvent(BundleClientWifiDirectEventType.WIFI_DIRECT_CLIENT_EXCHANGE_STARTED,
                                       device.deviceAddress);
        // make sure we are disconnected
        var oldGroupInfo = wifiDirectManager.getGroupInfo();
        if (oldGroupInfo != null) {
            try {
                wifiDirectManager.disconnect().get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.log(WARNING, "Failed to disconnect from group: " + oldGroupInfo.getNetworkName(), e);
            }
        }
        try {
            var newGroup = connectTo(device).get(10, TimeUnit.SECONDS);
            return bundleTransmission.doExchangeWithTransport(device.deviceAddress, device.deviceName,
                                                              wifiDirectManager.getGroupOwnerAddress().getHostAddress(),
                                                              7777);
        } catch (Throwable e) {
            logger.log(WARNING, "Failed to connect to " + device.deviceName, e);
        } finally {
            wifiDirectManager.disconnect();
            broadcastBundleClientWifiEvent(BundleClientWifiDirectEventType.WIFI_DIRECT_CLIENT_EXCHANGE_FINISHED,
                                           device.deviceAddress);
        }
        return new BundleExchangeCounts(0, 0);
    }

    private CompletableFuture<WifiP2pGroup> connectTo(WifiP2pDevice transport) {
        var connectedFuture = new CompletableFuture<WifiP2pGroup>();
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

    public void discoverPeers() {
        wifiDirectManager.discoverPeers();
    }

    public CompletableFuture<BundleExchangeCounts> initiateExchange(String deviceAddress) {
        var completableFuture = new CompletableFuture<BundleExchangeCounts>();
        var device = wifiDirectManager.getPeerList().stream().filter(peer -> peer.deviceAddress.equals(deviceAddress))
                .findFirst().orElse(null);
        if (device == null) {
            completableFuture.complete(ZERO_BUNDLE_EXCHANGE_COUNTS);
            return completableFuture;
        }
        // we want to use the executor to make sure that only one exchange is going on at a time
        periodicExecutor.submit(() -> {
            try {
                var bundleExchangeCounts = exchangeWith(device);
                completableFuture.complete(bundleExchangeCounts);
            } catch (Exception e) {
                logger.log(WARNING, "Failed to initiate exchange with " + device.deviceName, e);
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

    public InetAddress getGroupOwnerAddress() {
        return wifiDirectManager.getGroupOwnerAddress();
    }

    public WifiP2pGroup getGroupInfo() {
        return wifiDirectManager.getGroupInfo();
    }

    public boolean isDiscoveryActive() {
        return wifiDirectManager.isDiscoveryActive();
    }

    public BundleTransmission.RecentTransport getPeer(String deviceAddress) {
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
