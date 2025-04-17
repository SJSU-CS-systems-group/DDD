package net.discdd.bundleclient;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.Manifest.permission.CHANGE_WIFI_STATE;
import static android.Manifest.permission.NEARBY_WIFI_DEVICES;

import static java.util.logging.Level.INFO;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.wifi.aware.DiscoverySession;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.ServiceDiscoveryInfo;
import android.net.wifi.aware.SubscribeConfig;
import android.os.Binder;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;

import net.discdd.wifiaware.WifiAwareHelper;
import net.discdd.wifiaware.WifiAwareHelper.WiFiAwareException;
import net.discdd.wifiaware.WifiAwareStateListener;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class BundleClientWifiAwareService extends Service implements WifiAwareStateListener {
    public static final String NET_DISCDD_BUNDLECLIENT_LOG_ACTION = "net.discdd.bundleclient.CLIENT_LOG";
    public static final String NET_DISCDD_BUNDLECLIENT_WIFI_EVENT_ACTION = "net.discdd.bundleclient.WIFI_EVENT";
    public static final String WIFI_AWARE_EVENT_EXTRA = "wifiAwareEvent";
    private WifiAwareHelper wifiAwareHelper;
    private DiscoverySession session;
    private static final Logger logger = Logger.getLogger(BundleClientWifiAwareService.class.getName());
    private final IBinder binder = new BundleClientWifiAwareServiceBinder();

    @Override
    public void onCreate() {
        super.onCreate();
        logger.log(INFO, "Service onCreate called");

        // Initialize WifiAwareHelper if it hasn't been set yet by the constructor
        if (wifiAwareHelper == null) {
            try {
                wifiAwareHelper = new WifiAwareHelper(getApplicationContext());
                wifiAwareHelper.initialize();
                logger.log(INFO, "WifiAwareHelper initialized in onCreate");
            } catch (Exception e) {
                logger.log(INFO, "Failed to initialize WifiAwareHelper", e);
            }
        }

        createNotificationChannel();
        startForeground(1, createNotification());
    }

    private void createNotificationChannel() {
        NotificationChannel channel =
                new NotificationChannel("wifi_aware_service_channel", "WiFi Aware Service",
                                        NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Handles WiFi Aware operations");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        return new Notification.Builder(this, "wifi_aware_service_channel")
                .setContentTitle("WiFi Aware Service")
                .setContentText("Running WiFi Aware operations")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build();
    }

    public void setSession(DiscoverySession session) {
        this.session = session;
        logger.info("Discovery session initialized successfully");
    }

    public CompletableFuture<InetSocketAddress> connectToTransport(PeerHandle peerHandle) {
        CompletableFuture<InetSocketAddress> future = new CompletableFuture<>();
        if (session == null) {
            future.completeExceptionally(new WifiAwareHelper.WiFiAwareException(
                    "Subscriber session has not been started yet"));
            return future;
        }
        var connectivityFuture = wifiAwareHelper.getConnectivityManager(session, peerHandle, -1);
        connectivityFuture.whenComplete((ni, t) -> {
            if (t != null) {
                future.completeExceptionally(t);
            } else if (ni == null) {
                future.completeExceptionally(new WifiAwareHelper.WiFiAwareException(
                        "No peer aware info"));
            } else {
                var peerIpv6 = ni.getPeerIpv6Addr();
                int peerPort = ni.getPort();
                future.complete(new InetSocketAddress(peerIpv6, peerPort));
            }
        });
        return future;
    }


    // should return void
    @RequiresPermission(allOf = {
            ACCESS_WIFI_STATE,
            CHANGE_WIFI_STATE,
            ACCESS_FINE_LOCATION,
            NEARBY_WIFI_DEVICES}, conditional = true)
    public void startDiscovery(final String serviceName,
                                                       final byte[] serviceSpecificInfo,
                                                       List<byte[]> matchFilter,
                                                              Consumer<WifiAwareHelper.PeerMessage> messageReceiver,
                                                              Consumer<ServiceDiscoveryInfo> serviceDiscoveryReceiver,
                                                              Consumer<PeerHandle> serviceLostReceiver) throws WiFiAwareException {
//        if (wifiAwareHelper == null) {
//            throw new WiFiAwareException("WifiAwareHelper is null. Ensure it is initialized before calling startDiscovery.");
//        }
//        if (wifiAwareHelper.getWifiAwareSession() == null) {
//            throw new WiFiAwareException("Wi-Fi Aware session is not initialized");
//        }

        var configBuilder = new SubscribeConfig.Builder();
        if (serviceName != null) configBuilder.setServiceName(serviceName);
        if (serviceSpecificInfo != null) configBuilder.setServiceSpecificInfo(serviceSpecificInfo);
        if (matchFilter != null) configBuilder.setMatchFilter(matchFilter);

        var callbackHandler = new DiscoverySessionCallbackHandler(serviceLostReceiver,
                                                                  serviceDiscoveryReceiver,
                                                                  messageReceiver);
        wifiAwareHelper.getWifiAwareSession().subscribe(configBuilder.build(), callbackHandler, null);
    }

    public void unsubscribe() {
        if (session != null) {
            session.close();
            session = null;
        } else {
            logger.warning("Attempted to unsubscribe, but session was null.");
        }
    }

    @Override
    public void onReceiveAction(WifiAwareHelper.WifiAwareEvent action) {
            switch (action.type()) {
                case WIFI_AWARE_MANAGER_INITIALIZED:
                    logger.info("WifiAwareManager initialized");
                    break;

                case WIFI_AWARE_MANAGER_AVAILABILITY_CHANGED:
                    boolean isAvailable = wifiAwareHelper.isWifiAwareAvailable();
                    logger.info("WiFi Aware availability changed: " + (isAvailable ? "available" : "unavailable"));

                    if (!isAvailable && session != null) {
                        logger.info("Session likely invalid due to WiFi Aware becoming unavailable");
                    }
                    break;

                case WIFI_AWARE_MANAGER_TERMINATED:
                    logger.info("WiFi Aware session terminated: " +
                                        (action.message() != null ? action.message() : "No details provided"));
                    break;

                case WIFI_AWARE_MANAGER_FAILED:
                    logger.severe("WiFi Aware operation failed: " +
                                          (action.message() != null ? action.message() : "No details provided"));
                    break;

                default:
                    logger.info("Received WiFi Aware event type: " + action.type());
                    break;
            }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public WifiAwareHelper getWifiAwareHelper() {
        return wifiAwareHelper;
    }

    public class BundleClientWifiAwareServiceBinder extends Binder {
        BundleClientWifiAwareService getService() { return BundleClientWifiAwareService.this;}
    }
}

