package net.discdd.bundleclient.service;

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
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.StrictMode;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.annotation.StringRes;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import net.discdd.bundleclient.BuildConfig;
import net.discdd.bundleclient.R;
import net.discdd.bundleclient.service.wifiDirect.DDDWifiDirect;
import net.discdd.client.bundletransmission.ClientBundleTransmission;
import net.discdd.client.bundletransmission.ClientBundleTransmission.BundleExchangeCounts;
import net.discdd.client.bundletransmission.ClientBundleTransmission.Statuses;
import net.discdd.client.bundletransmission.TransportDevice;
import net.discdd.bundleclient.utils.ServerMessageAduHandler;
import net.discdd.datastore.providers.MessageProvider;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.model.ADU;
import net.discdd.pathutils.ClientPaths;
import net.discdd.utils.DDDFixedRateScheduler;
import net.discdd.utils.LogUtil;
import net.discdd.utils.UserLogRepository;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import static java.lang.String.format;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;
import static net.discdd.utils.Constants.GRPC_LONG_TIMEOUT_MS;

// the service is usually formatting messages for the log rather than users, so don't complain about locales
@SuppressLint("DefaultLocale")
public class BundleClientService extends Service {
    public static final String NET_DISCDD_BUNDLECLIENT_WIFI_ACTION = "net.discdd.bundleclient.WIFI_EVENT";
    public static final String NET_DISCDD_BUNDLECLIENT_SETTINGS = "net.discdd.bundleclient";
    public static final String DDDWIFI_EVENT_EXTRA = "DDDWifiEvent";
    public static final String BUNDLE_CLIENT_TRANSMISSION_EVENT_EXTRA = "BundleClientTransmissionEvent";
    public static final String NET_DISCDD_BUNDLECLIENT_SETTING_BACKGROUND_EXCHANGE = "background_exchange";
    public static final String NET_DISCDD_BUNDLECLIENT_DEVICEADDRESS_EXTRA = "deviceAddress";
    private static final Logger logger = Logger.getLogger(BundleClientService.class.getName());
    public static BundleClientService instance;
    private static SharedPreferences preferences;
    private final IBinder binder = new BundleClientServiceBinder();
    private DDDFixedRateScheduler<BundleExchangeCounts[]> fixedRateSched;
    // per https://developer.android.com/reference/android/content/SharedPreferences the listener needs
    // to be a strong reference
    final private SharedPreferences.OnSharedPreferenceChangeListener onSharedPreferenceChangeListener;
    // this is used by processIncomingADU to track which appIds have been inserted
    final private Set<String> insertedAppIds = Collections.synchronizedSet(new HashSet<>());
    ConnectivityManager connectivityManager;
    ConnectivityManager.NetworkCallback networkCallback;
    private DDDWifi dddWifi;
    private ClientBundleTransmission bundleTransmission;
    final private Observer<? super DDDWifiEventType> liveDataObserver = this::broadcastWifiEvent;
    private MutableLiveData<DDDWifiEventType> eventsLiveData;

    public BundleClientService() {
        super();
        onSharedPreferenceChangeListener = (sharedPreferences, key) -> {
            if (NET_DISCDD_BUNDLECLIENT_SETTING_BACKGROUND_EXCHANGE.equals(key)) {
                processBackgroundExchangeSetting();
            }
        };
    }

    private static BundleExchangeCounts failedExchangeCounts(TransportDevice device) {
        return new BundleExchangeCounts(device, Statuses.FAILED, Statuses.FAILED, null);
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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        preferences = getSharedPreferences(NET_DISCDD_BUNDLECLIENT_SETTINGS, MODE_PRIVATE);
        preferences.registerOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener);
        fixedRateSched = new DDDFixedRateScheduler<>(getApplicationContext(), this::exchangeWithTransports);
        processBackgroundExchangeSetting();
        startForeground();
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                checkValidNetwork();
            }

            @Override
            public void onLost(Network network) {
                eventsLiveData.postValue(DDDWifiEventType.DDDWIFI_DISCONNECTED);
            }
        };
        connectivityManager.registerDefaultNetworkCallback(networkCallback);
        checkValidNetwork();
        return START_STICKY;
    }

    public boolean isNetworkValid() {
        LinkProperties linkProperties = connectivityManager.getLinkProperties(connectivityManager.getActiveNetwork());
        if (linkProperties != null && linkProperties.getLinkAddresses() != null) {
            for (LinkAddress address : linkProperties.getLinkAddresses()) {
                InetAddress inet = address.getAddress();
                if (inet instanceof Inet4Address) {
                    String ipAddress = inet.getHostAddress();
                    // checks if we have a 192.168.49.x addresses, which are used by wifi direct
                    if (ipAddress != null && ipAddress.startsWith("192.168.49.")) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void checkValidNetwork() {
        if (isNetworkValid()) {
            eventsLiveData.postValue(DDDWifiEventType.DDDWIFI_CONNECTED);
        } else {
            eventsLiveData.postValue(DDDWifiEventType.DDDWIFI_DISCONNECTED);
        }
    }

    public DDDWifi getDddWifi() {
        return dddWifi;
    }

    public ConnectivityManager.NetworkCallback getNetworkCallback() {
        return networkCallback;
    }

    private void processBackgroundExchangeSetting() {
        var backgroundExchange = getBackgroundExchangeSetting(preferences);
        fixedRateSched.setPeriodInMinutes(backgroundExchange);
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

        try {
            //Application context
            var resources = getApplicationContext().getResources();
            try (InputStream inServerIdentity =
                         resources.openRawResource(net.discdd.android_core.R.raw.server_identity);
                 InputStream inServerSignedPre =
                         resources.openRawResource(net.discdd.android_core.R.raw.server_signed_pre);
                 InputStream inServerRatchet =
                         resources.openRawResource(net.discdd.android_core.R.raw.server_ratchet)) {

                ClientPaths clientPaths = new ClientPaths(getApplicationContext().getDataDir().toPath(),
                                                          inServerIdentity.readAllBytes(),
                                                          inServerSignedPre.readAllBytes(),
                                                          inServerRatchet.readAllBytes());
                bundleTransmission = new ClientBundleTransmission(clientPaths, this::processIncomingADU);
            } catch (IOException e) {
                logger.log(SEVERE, "[SEC]: Failed to initialize Server Keys", e);
            }

            var dddWifiDirect = new DDDWifiDirect(this);
            this.dddWifi = dddWifiDirect;
            this.dddWifi.getEventLiveData().observeForever(liveDataObserver);
            eventsLiveData = (MutableLiveData<DDDWifiEventType>) dddWifiDirect.getEventLiveData();
            try {
                dddWifiDirect.initialize();
            } catch (Exception e) {
                logger.log(SEVERE, "Failed to initialize DDDWifiDirect", e);
            }
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to initialize BundleTransmission", e);
        }
    }

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
            if (ServerMessageAduHandler.APP_ID.equals(adu.getAppId())) {
                ServerMessageAduHandler.handle(getApplicationContext(), adu);
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // hacky, but chatGPT seems to like it.
        // this allows MessageProvider to access the service
        instance = this;
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build());
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build());
        }
    }

    @Override
    public void onDestroy() {
        instance.stopSelf();
        instance = null;
        if (dddWifi != null) {
            dddWifi.getEventLiveData().removeObserver(liveDataObserver);
            dddWifi.shutdown();
        }
        preferences.unregisterOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @SuppressLint("MissingPermission")
    private BundleExchangeCounts[] exchangeWithTransports() throws ExecutionException, InterruptedException,
            TimeoutException {
        var exchangeCounts = new ArrayList<BundleExchangeCounts>();
        try {
            dddWifi.startDiscovery().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.log(WARNING, "Failed to start discovery", e);
            broadcastBundleClientLogEvent(R.string.failed_to_start_discovery_s, e.getMessage());
            // not the end of the world, we can still try
        }
        var recentTransports = bundleTransmission.getRecentTransports();
        for (var transport : recentTransports) {
            if (transport.getDevice() instanceof DDDWifiDevice && ClientBundleTransmission.doesTransportHaveNewData(transport)) {
                var bc = exchangeWith((DDDWifiDevice) transport.getDevice());
                exchangeCounts.add(bc);
                logger.log(INFO,
                           format("Upload status: %s, Download status: %s",
                                  bc.uploadStatus().toString(),
                                  bc.downloadStatus().toString()));
            }
        }
        var bc = initiateServerExchange().get(GRPC_LONG_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        exchangeCounts.add(bc);
        return exchangeCounts.toArray(new BundleExchangeCounts[0]);
    }

    @RequiresPermission(allOf = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES })
    private BundleExchangeCounts exchangeWith(DDDWifiDevice device) {
        broadcastBundleClientWifiEvent(BundleClientTransmissionEventType.WIFI_DIRECT_CLIENT_EXCHANGE_STARTED,
                                       device.getWifiAddress());
        DDDWifiConnection connection = null;
        try {
            broadcastBundleClientLogEvent(R.string.connecting_to_s, device.getDescription());
            connection = dddWifi.connectTo(device).get(10, TimeUnit.SECONDS);

            if (connection == null || connection.getAddresses().isEmpty()) {
                broadcastBundleClientLogEvent(R.string.failed_to_connect_to_s, device.getDescription());
                return failedExchangeCounts(device);
            }
            var addr = connection.getAddresses().get(0);
            broadcastBundleClientLogEvent(R.string.connected_to_s_s,
                                                 device.getDescription(),
                                                 addr.getHostAddress());
            BundleExchangeCounts currentBundle =
                    bundleTransmission.doExchangeWithTransport(device, addr.getHostAddress(), 7777, true);
            broadcastBundleClientLogEvent(R.string.s_upload_s_download_s,
                                                 device.getDescription(),
                                                 statusesToString(currentBundle.uploadStatus()),
                                                 statusesToString(currentBundle.downloadStatus()));
            if (currentBundle.e() instanceof ClientBundleTransmission.RecencyException) {
                broadcastBundleClientLogEvent(R.string.not_exchanged_recently_s, currentBundle.e().getMessage());
            }
            String text1;
            String text2;
            if (currentBundle.uploadStatus() == Statuses.FAILED) {
                text1 = getString(R.string.Upload_Failed);
            } else if (currentBundle.uploadStatus() == Statuses.COMPLETE) {
                text1 = getString(R.string.Upload_Success);
            } else {
                text1 = getString(R.string.Upload_Empty);
            }
            if (currentBundle.downloadStatus() == Statuses.FAILED) {
                text2 = getString(R.string.Download_Failed);
            } else if (currentBundle.downloadStatus() == Statuses.COMPLETE) {
                text2 = getString(R.string.Download_Success);
            } else {
                text2 = getString(R.string.Download_Empty);
            }

            final String text = text1 + "\n" + text2;
            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(() -> Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG).show());
            return currentBundle;
        } catch (TimeoutException e) {
            logger.log(WARNING, "Timeout connecting to " + device.getDescription());
            broadcastBundleClientLogEvent(R.string.timeout_connecting_to_s, device.getDescription());
        } catch (Throwable e) {
            logger.log(WARNING, e.getMessage(), e);
            broadcastBundleClientLogEvent(R.string.could_not_start_exchange, e.getMessage());
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
        return failedExchangeCounts(device);
    }

    private String statusesToString(Statuses statuses) {
        return switch (statuses) {
            case COMPLETE -> "Complete";
            case FAILED -> "Failed";
            case EMPTY -> "Skipped";
        };
    }

    private void broadcastBundleClientLogEvent(@StringRes int resId, Object... args) {
        LogUtil.logUi(getApplicationContext(), logger, UserLogRepository.UserLogType.WIFI, INFO, resId, args);
    }

    /**
     * Broadcast a wifi event to any interested receivers.
     * THIS CODE ALSO PROCESSES EVENTS IT IS INTERESTED IN
     *
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

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this, "DDD-Client").setSmallIcon(R.drawable.bundleclient_icon)
                        .setContentTitle(getString(R.string.exchanging_with_transport))
                        .setContentText(getString(R.string.initiating_bundle_exchange))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(false)
                        .setOngoing(true);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(1002, builder.build());

        fixedRateSched.callItNow(() -> {
            try {
                var bundleExchangeCounts = exchangeWith(device);
                completableFuture.complete(bundleExchangeCounts);
                return new BundleExchangeCounts[] { bundleExchangeCounts };
            } catch (Exception e) {
                logger.log(WARNING, "Failed to initiate exchange with " + device.getDescription(), e);
                completableFuture.complete(failedExchangeCounts(device));
                throw e;
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
        var FAILED_EXCHANGE_COUNTS = failedExchangeCounts(TransportDevice.SERVER_DEVICE);
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            logger.info("Not connected to the internet, skipping server exchange");
            completableFuture.complete(FAILED_EXCHANGE_COUNTS);
            return completableFuture;
        }
        fixedRateSched.callItNow(() -> {
            var bc = FAILED_EXCHANGE_COUNTS;
            try {
                var serverAddress = preferences.getString("domain", "");
                var port = preferences.getInt("port", 0);
                if (serverAddress.isEmpty() || port == 0) {
                    completableFuture.complete(FAILED_EXCHANGE_COUNTS);
                } else {
                    bc = bundleTransmission.doExchangeWithTransport(TransportDevice.SERVER_DEVICE,
                                                                    serverAddress,
                                                                    port,
                                                                    false);
                    logger.log(INFO,
                               format("Upload status: %s, Download status: %s",
                                      bc.uploadStatus().toString(),
                                      bc.downloadStatus().toString()));
                    completableFuture.complete(bc);
                }
            } catch (Exception e) {
                logger.log(WARNING, "Failed to initiate exchange with server", e);
                completableFuture.complete(FAILED_EXCHANGE_COUNTS);
            }
            return new BundleExchangeCounts[] { bc };
        });
        return completableFuture;
    }

    public ClientBundleTransmission.RecentTransport[] getRecentTransports() {
        return bundleTransmission.getRecentTransports();
    }

    public boolean isDiscoveryActive() {
        return dddWifi.isDiscoveryActive();
    }

    public ClientBundleTransmission.RecentTransport getRecentTransport(DDDWifiDevice peer) {
        return Arrays.stream(bundleTransmission.getRecentTransports())
                .filter(rt -> peer.equals(rt.getDevice()))
                .findFirst()
                .orElse(new ClientBundleTransmission.RecentTransport(peer, GetRecencyBlobResponse.getDefaultInstance()));
    }

    public void notifyNewAdu() {
        if (preferences != null && getBackgroundExchangeSetting(preferences) > 0) {
            initiateServerExchange();
        }
    }

    public void peersUpdated() {
        dddWifi.listDevices().forEach(device -> bundleTransmission.processDiscoveredPeer(device, device.getRecencyBlob()));
        // expire peers that haven't been seen for a minute
        long expirationTime = System.currentTimeMillis() - 60 * 1000;
        bundleTransmission.expireNotSeenPeers(expirationTime);
    }

    public void wifiPermissionGranted() {
        dddWifi.wifiPermissionGranted();
    }

    public ClientBundleTransmission getBundleTransmission() {
        return bundleTransmission;
    }

    public enum BundleClientTransmissionEventType {
        WIFI_DIRECT_CLIENT_EXCHANGE_STARTED, WIFI_DIRECT_CLIENT_EXCHANGE_FINISHED
    }

    public class BundleClientServiceBinder extends Binder {
        public BundleClientService getService() {return BundleClientService.this;}
    }

}
