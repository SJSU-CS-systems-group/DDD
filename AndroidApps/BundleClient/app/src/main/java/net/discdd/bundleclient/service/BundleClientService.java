package net.discdd.bundleclient.service;

import static java.lang.String.format;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;

import android.os.Looper;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.lifecycle.Observer;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.discdd.bundleclient.R;
import net.discdd.bundleclient.service.wifiDirect.DDDWifiDirect;
import net.discdd.client.bundlesecurity.BundleSecurity;
import net.discdd.client.bundletransmission.BundleTransmission;
import net.discdd.client.bundletransmission.BundleTransmission.Statuses;
import net.discdd.client.bundletransmission.BundleTransmission.BundleExchangeCounts;
import net.discdd.client.bundletransmission.TransportDevice;
import net.discdd.datastore.providers.MessageProvider;
import net.discdd.model.ADU;
import net.discdd.pathutils.ClientPaths;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

// the service is usually formatting messages for the log rather than users, so don't complain about locales
@SuppressLint("DefaultLocale")
public class BundleClientService extends Service {
    public static final String NET_DISCDD_BUNDLECLIENT_LOG_ACTION = "net.discdd.bundleclient.CLIENT_LOG";
    public static final String NET_DISCDD_BUNDLECLIENT_WIFI_ACTION = "net.discdd.bundleclient.WIFI_EVENT";
    public static final String NET_DISCDD_BUNDLECLIENT_SETTINGS = "net.discdd.bundleclient";
    public static final String DDDWIFI_EVENT_EXTRA = "DDDWifiEvent";
    public static final String BUNDLE_CLIENT_TRANSMISSION_EVENT_EXTRA = "BundleClientTransmissionEvent";
    public static final String NET_DISCDD_BUNDLECLIENT_SETTING_BACKGROUND_EXCHANGE = "background_exchange";
    public static final String NET_DISCDD_BUNDLECLIENT_DEVICEADDRESS_EXTRA = "deviceAddress";
    private static final Logger logger = Logger.getLogger(BundleClientService.class.getName());
    private static final BundleExchangeCounts FAILED_EXCHANGE_COUNTS = new BundleExchangeCounts(Statuses.FAILED,Statuses.FAILED);
    private static SharedPreferences preferences;
    private final IBinder binder = new BundleClientServiceBinder();
    private final ScheduledExecutorService periodicExecutor = Executors.newSingleThreadScheduledExecutor();
    PeriodicRunnable periodicRunnable = new PeriodicRunnable();
    private DDDWifi dddWifi;
    private BundleTransmission bundleTransmission;
    ConnectivityManager connectivityManager;
    final private Observer<? super DDDWifiEventType> liveDataObserver = this::broadcastWifiEvent;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        preferences = getSharedPreferences(NET_DISCDD_BUNDLECLIENT_SETTINGS, MODE_PRIVATE);
        preferences.registerOnSharedPreferenceChangeListener((sharedPreferences, key) -> {
            if (NET_DISCDD_BUNDLECLIENT_SETTING_BACKGROUND_EXCHANGE.equals(key)) {
                processBackgroundExchangeSetting();
            }
        });
        processBackgroundExchangeSetting();
        startForeground();
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        return START_STICKY;
    }

    public DDDWifi getDddWifi() {
        return dddWifi;
    }

    public static int getBackgroundExchangeSetting(SharedPreferences preferences) {
        try {
            return preferences.getInt(NET_DISCDD_BUNDLECLIENT_SETTING_BACKGROUND_EXCHANGE, 0);
        } catch (ClassCastException e) {
            // we are transitioning from boolean to int, so we need to handle the case where the value is a boolean
            int value = preferences.getBoolean(NET_DISCDD_BUNDLECLIENT_SETTING_BACKGROUND_EXCHANGE, false) ? 1 : 0;
            preferences.edit()
                    .remove(NET_DISCDD_BUNDLECLIENT_SETTING_BACKGROUND_EXCHANGE)
                    .putInt(NET_DISCDD_BUNDLECLIENT_SETTING_BACKGROUND_EXCHANGE, value)
                    .apply();
            return value;
        }
    }

    private void processBackgroundExchangeSetting() {
        var backgroundExchange = getBackgroundExchangeSetting(preferences);
        if (backgroundExchange > 0) {
            periodicRunnable.schedule(backgroundExchange);
        } else {
            periodicRunnable.cancel();
        }
        logger.info("Client background exchange changed to " + backgroundExchange);
    }

    @SuppressLint("MissingPermission")
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

        var dddWifiDirect = new DDDWifiDirect(this);
        this.dddWifi = dddWifiDirect;
        this.dddWifi.getEventLiveData().observeForever(liveDataObserver);
        try {
            dddWifiDirect.initialize();
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to initialize DDDWifiDirect", e);
        }
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

    // this is used by processIncomingADU to track which appIds have been inserted
    final private Set<String> insertedAppIds = Collections.synchronizedSet(new HashSet<>());
    private void processIncomingADU(ADU adu) {
        if (adu == null) {
            // the null adu is used to signal that the current batch of processing ADUs is done
            synchronized (insertedAppIds) {
                for (var appId : insertedAppIds) {
                    var uri = Uri.withAppendedPath(MessageProvider.URL, appId);
                    getApplicationContext().getContentResolver().notifyChange(uri, null);
                }
                insertedAppIds.clear();
            }
        } else {
            insertedAppIds.add(adu.getAppId());
        }
    }

    public static BundleClientService instance;

    @Override
    public void onCreate() {
        super.onCreate();
        // hacky, but chatGPT seems to like it.
        // this allows MessageProvider to access the service
        instance = this;
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (dddWifi != null) {
            dddWifi.getEventLiveData().removeObserver(liveDataObserver);
            dddWifi.shutdown();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @SuppressLint("MissingPermission")
    private void exchangeWithTransports() {
        var recentTransports = bundleTransmission.getRecentTransports();
        for (var transport : recentTransports) {
            var bc = exchangeWith((DDDWifiDevice) transport.getDevice());
            logger.log(INFO, format("Upload status: %s, Download status: %s",bc.uploadStatus().toString(), bc.downloadStatus().toString()));
        }
        initiateServerExchange();
    }

    @RequiresPermission(allOf = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES })
    private BundleExchangeCounts exchangeWith(DDDWifiDevice device) {
        broadcastBundleClientWifiEvent(BundleClientTransmissionEventType.WIFI_DIRECT_CLIENT_EXCHANGE_STARTED,
                                       device.getWifiAddress());
        DDDWifiConnection connection = null;
        try {
            broadcastBundleClientLogEvent(format("Connecting to %s", device.getDescription()));
            connection = dddWifi.connectTo(device).get(10, TimeUnit.SECONDS);

            if (connection == null || connection.getAddresses().isEmpty()) {
                broadcastBundleClientLogEvent("Failed to connect to " + device.getDescription());
                return FAILED_EXCHANGE_COUNTS;
            }
            var addr = connection.getAddresses().get(0);
            broadcastBundleClientLogEvent(format("Connected to %s (%s)", device.getDescription(), addr.getHostAddress()));
            BundleExchangeCounts currentBundle = bundleTransmission.doExchangeWithTransport(device,
                                                                                            addr.getHostAddress(),
                                                                                            7777,
                                                                                            true);
            broadcastBundleClientLogEvent(format("%s upload: %s download: %s", device.getDescription(),
                                                                                 statusesToString(currentBundle.uploadStatus()),
                                                                                 statusesToString(currentBundle.downloadStatus())));
            String text1;
            String text2;
            if(currentBundle.uploadStatus() == Statuses.FAILED){
                text1 = getString(R.string.Upload_Failed);
            }else if(currentBundle.uploadStatus() == Statuses.COMPLETE){
                text1 = getString(R.string.Upload_Success);
            }else{
                text1 = getString(R.string.Upload_Empty);
            }
            if(currentBundle.downloadStatus() == Statuses.FAILED){
                text2 = getString(R.string.Download_Failed);
            }else if(currentBundle.downloadStatus() == Statuses.COMPLETE){
                text2 = getString(R.string.Download_Success);
            }else{
                text2 = getString(R.string.Download_Empty);
            }

            final String text = text1 + "\n" + text2;
            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(() -> Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG).show());
            return currentBundle;

        } catch (Throwable e) {
            broadcastBundleClientLogEvent(e.getLocalizedMessage());
            logger.log(WARNING, e.getMessage(), e);

        } finally {
            try {
                if (connection != null) {
                    dddWifi.disconnectFrom(connection).get(10, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                logger.log(WARNING, "Problem disconnecting from " + device.getDescription(), e);
            }
            dddWifi.startDiscovery();
            broadcastBundleClientWifiEvent(BundleClientTransmissionEventType.WIFI_DIRECT_CLIENT_EXCHANGE_FINISHED,
                                           device.getWifiAddress());

        }
        return FAILED_EXCHANGE_COUNTS;
    }

    private String statusesToString(Statuses statuses) {
        return switch (statuses) {
            case COMPLETE -> "Complete";
            case FAILED -> "Failed";
            case EMPTY -> "Skipped";
        };
    }

    private void broadcastBundleClientLogEvent(String message) {
        var intent = new Intent(NET_DISCDD_BUNDLECLIENT_LOG_ACTION);
        intent.putExtra("message", message);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    /**
     * Broadcast a wifi event to any interested receivers.
     * THIS CODE ALSO PROCESSES EVENTS IT IS INTERESTED IN
     * @param type the type of event
     */
    public void broadcastWifiEvent(DDDWifiEventType type) {
        if (type == DDDWifiEventType.DDDWIFI_PEERS_CHANGED) {
            peersUpdated();
        }
        var intent = new Intent(NET_DISCDD_BUNDLECLIENT_WIFI_ACTION);
        intent.putExtra(DDDWIFI_EVENT_EXTRA, type);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    public void broadcastBundleClientWifiEvent(BundleClientTransmissionEventType event, String deviceAddress) {
        //var intent = new Intent(getApplicationContext(), TransportWifiDirectFragment.class);
        var intent = new Intent(NET_DISCDD_BUNDLECLIENT_WIFI_ACTION);
        intent.putExtra(BUNDLE_CLIENT_TRANSMISSION_EVENT_EXTRA, event);
        intent.putExtra(NET_DISCDD_BUNDLECLIENT_DEVICEADDRESS_EXTRA, deviceAddress);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    public String getClientId() {
        return bundleTransmission.getBundleSecurity().getClientSecurity().getClientID();
    }

    public void discoverPeers() {
        dddWifi.startDiscovery();
    }

    @RequiresPermission(allOf = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES })
    @SuppressLint("NotificationPermission")
    public CompletableFuture<BundleExchangeCounts> initiateExchange(DDDWifiDevice device) {
        var completableFuture = new CompletableFuture<BundleExchangeCounts>();
        NotificationChannel channel =
                new NotificationChannel("DDD-Exchange", "DDD Bundle Client", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Initiating Bundle Exchange...");

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "DDD-Client")
                .setSmallIcon(R.drawable.bundleclient_icon)
                .setContentTitle(getString(R.string.exchanging_with_transport))
                .setContentText(getString(R.string.initiating_bundle_exchange))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(false)
                .setOngoing(true);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(1002, builder.build());

        // we want to use the executor to make sure that only one exchange is going on at a time
        periodicExecutor.submit(() -> {
            try {
                var bundleExchangeCounts = exchangeWith(device);
                completableFuture.complete(bundleExchangeCounts);
            } catch (Exception e) {
                logger.log(WARNING, "Failed to initiate exchange with " + device.getDescription(), e);
                completableFuture.complete(FAILED_EXCHANGE_COUNTS);
            }
        });

        completableFuture.thenApply(ex -> {
            notificationManager.cancel(1002);
            return null;
        });

        return completableFuture;
    }

    public CompletableFuture<BundleExchangeCounts> initiateServerExchange() {
        var completableFuture = new CompletableFuture<BundleExchangeCounts>();
        var connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        var activeNetwork = connectivityManager.getActiveNetwork();
        var caps = activeNetwork == null ? null : connectivityManager.getNetworkCapabilities(activeNetwork);
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            logger.info("Not connected to the internet, skipping server exchange");
            completableFuture.complete(FAILED_EXCHANGE_COUNTS);
            return completableFuture;
        }
        periodicExecutor.submit(() -> {
            try {
                var serverAddress = preferences.getString("domain", "");
                var port = preferences.getInt("port", 0);
                if (serverAddress.isEmpty() || port == 0) {
                    completableFuture.complete(FAILED_EXCHANGE_COUNTS);
                    return;
                }
                var bundleExchangeCounts =
                        bundleTransmission.doExchangeWithTransport(TransportDevice.SERVER_DEVICE, serverAddress,
                                                                   port, false);
                logger.log(INFO, format("Upload status: %s, Download status: %s",bundleExchangeCounts.uploadStatus().toString(), bundleExchangeCounts.downloadStatus().toString()));
                completableFuture.complete(bundleExchangeCounts);
            } catch (Exception e) {
                logger.log(WARNING, "Failed to initiate exchange with server", e);
                completableFuture.complete(FAILED_EXCHANGE_COUNTS);
            }
        });
        return completableFuture;
    }

    public BundleTransmission.RecentTransport[] getRecentTransports() {
        return bundleTransmission.getRecentTransports();
    }

    public boolean isDiscoveryActive() {
        return dddWifi.isDiscoveryActive();
    }

    public BundleTransmission.RecentTransport getRecentTransport(DDDWifiDevice peer) {
        return Arrays.stream(bundleTransmission.getRecentTransports())
                .filter(rt -> peer.equals(rt.getDevice())).findFirst()
                .orElse(new BundleTransmission.RecentTransport(peer));
    }

    public void notifyNewAdu() {
        if (preferences != null && getBackgroundExchangeSetting(preferences) > 0) {
            initiateServerExchange();
        }
    }

    public void peersUpdated() {
        dddWifi.listDevices().forEach(bundleTransmission::processDiscoveredPeer);
        // expire peers that haven't been seen for a minute
        long expirationTime = System.currentTimeMillis() - 60 * 1000;
        bundleTransmission.expireNotSeenPeers(expirationTime);
    }

    public enum BundleClientTransmissionEventType {
        WIFI_DIRECT_CLIENT_EXCHANGE_STARTED, WIFI_DIRECT_CLIENT_EXCHANGE_FINISHED
    }

    private class PeriodicRunnable implements Runnable {
        private ScheduledFuture<?> scheduledFuture;

        synchronized public void schedule(int minutes) {
            if (scheduledFuture == null) {
                logger.info(format("Scheduling periodic exchange with transports every %d minutes", minutes));
                scheduledFuture = periodicExecutor.scheduleWithFixedDelay(this, 0, minutes,
                                                                          TimeUnit.MINUTES);
            }
        }

        synchronized public void cancel() {
            if (scheduledFuture != null) {
                logger.warning("Cancelling periodic exchange with transports");
                scheduledFuture.cancel(false);
                scheduledFuture = null;
            }
        }

        @Override
        public void run() {
            logger.info("Periodic exchange with transports started");
            BundleClientService.this.exchangeWithTransports();
        }
    }

    public class BundleClientServiceBinder extends Binder {
        public BundleClientService getService() {return BundleClientService.this;}
    }

    public BundleTransmission getBundleTransmission() {
        return bundleTransmission;
    }
}