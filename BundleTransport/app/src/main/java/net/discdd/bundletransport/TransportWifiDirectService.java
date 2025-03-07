package net.discdd.bundletransport;

import static net.discdd.wifidirect.WifiDirectManager.WifiDirectEventType.WIFI_DIRECT_MANAGER_SERVICE_DISCOVERED;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

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
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.discdd.android.fragments.LogFragment;
import net.discdd.bundlerouting.service.BundleExchangeServiceImpl;
import net.discdd.pathutils.TransportPaths;
import net.discdd.wifidirect.DiscoveredService;
import net.discdd.wifidirect.WifiDirectManager;
import net.discdd.wifidirect.WifiDirectStateListener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * This service handles the Wifi Direct group and the gRPC server.
 * It runs in the background and is designed to stay running even if the
 * app goes away.
 * Wifi Direct function calls from the App are passed through to this
 * service to handle using the TransportWifiDirectServiceBinder which
 * returns a reference to this service.
 */
public class TransportWifiDirectService extends Service
        implements WifiDirectStateListener, BundleExchangeServiceImpl.BundleExchangeEventListener {
    public static final String NET_DISCDD_BUNDLETRANSPORT_CLIENT_LOG_ACTION = "net.discdd.bundletransport.CLIENT_LOG";
    public static final String NET_DISCDD_BUNDLETRANSPORT_WIFI_EVENT_ACTION = "net.discdd.bundletransport.WIFI_EVENT";
    private static final Logger logger = Logger.getLogger(TransportWifiDirectService.class.getName());
    public static final String WIFI_DIRECT_PREFERENCES = "wifi_direct";
    public static final String WIFI_DIRECT_PREFERENCE_BG_SERVICE = "background_wifi";
    private final IBinder binder = new TransportWifiDirectServiceBinder();
    private final RpcServer grpcServer = new RpcServer(this);
    private TransportPaths transportPaths;
    private WifiDirectManager wifiDirectManager;
    private SharedPreferences sharedPreferences;
    Context getApplicationContext;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        this.transportPaths = new TransportPaths(getApplicationContext().getExternalFilesDir(null).toPath());
        super.onStartCommand(intent, flags, startId);
        // TransportWifiDirectService doesn't use LogFragment directly, but we do want our
        // logs to go to its logger
        LogFragment.registerLoggerHandler();
        sharedPreferences = getSharedPreferences(WIFI_DIRECT_PREFERENCES, Context.MODE_PRIVATE);
        logger.log(INFO,
                   "Starting " + TransportWifiDirectService.class.getName() + " with flags " + flags + " and startId " +
                           startId);
        startForeground();
        logger.log(INFO,
                   "Started " + TransportWifiDirectService.class.getName() + " with flags " + flags + " and startId " +
                           startId);
        return START_STICKY;
    }

    private void startForeground() {
        try {
            NotificationChannel channel = new NotificationChannel("DDD-Transport", "DDD Bundle Transport",
                                                                  NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("DDD Transport Service");

            var notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);

            Notification notification =
                    new NotificationCompat.Builder(this, "DDD-Transport").setContentTitle("DDD Bundle Transport")
                            .build();
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            ServiceCompat.startForeground(this, 1, notification, type);
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to start foreground service", e);
        }

        wifiDirectManager = new WifiDirectManager(this, this);
        wifiDirectManager.initialize();

        // Registers a local service for service discovery
        startRegistration();
    }

    /**
     * This method registers a local service for service discovery
     * Once registered, it automatically responds to service discovery requests from bundle clients
     */

    private void startRegistration() {
        if (!wifiDirectManager.getWifiDirectEnabled()) {
            logger.log(INFO, "WiFi Direct is not enabled. Retrying in 1 second...");

            // Retry in 1 second to wait for wifi direct to be enabled
            new Handler(Looper.getMainLooper()).postDelayed(this::startRegistration, 1000);
            return;
        }

        WifiP2pManager manager = wifiDirectManager.getManager();
        WifiP2pManager.Channel channel = wifiDirectManager.getChannel();

        if (manager == null || channel == null) {
            logger.log(SEVERE, "WiFi P2P Manager or Channel is null, aborting registration");
            new Handler(Looper.getMainLooper()).postDelayed(this::startRegistration, 5000);
            return;
        }

        String deviceName = wifiDirectManager.getDeviceName();
        logger.log(INFO, "Device name: " + deviceName);

        // Create the service record with available info
        Map<String, String> record = new HashMap<>();
        record.put("device_name", deviceName);
        record.put("service_type", "ddd_transport_service");
        record.put("port", "7777");

        WifiP2pDnsSdServiceInfo serviceInfo =
                WifiP2pDnsSdServiceInfo.newInstance(
                        "ddd_transport_" + deviceName,
                        "_dddtransport._tcp",
                        record);

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

        manager.addLocalService(
                channel,
                serviceInfo,
                new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {

                        logger.log(INFO, "Service registered successfully with records: " + record);
                    }

                    @Override
                    public void onFailure(int reason) {
                        logger.log(INFO, "Failed to register service. Reason: " + reason);
                        // Retry on failure
                        new Handler(Looper.getMainLooper()).postDelayed(() -> startRegistration(), 5000);
                    }
                });
    }

    @Override
    public void onDestroy() {
        if (wifiDirectManager != null) {
            wifiDirectManager.shutdown();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onReceiveAction(WifiDirectManager.WifiDirectEvent action) {
        if (action.type() == WIFI_DIRECT_MANAGER_SERVICE_DISCOVERED) {
            List<DiscoveredService> discoveredServices = getDiscoveredServices();

            if (discoveredServices.isEmpty()) {
                appendToClientLog("No services discovered. Shutting down gRPC server");
                stopRpcServer();
            } else {
                appendToClientLog(String.format("%d services discovered. Starting gRPC server", discoveredServices.size()));
                startRpcServer();
            }
        }
        broadcastWifiEvent(action);
    }

    public List<DiscoveredService> getDiscoveredServices() {
        return wifiDirectManager.getDiscoveredServices();
    }

    private void startRpcServer() {
        synchronized (grpcServer) {
            if (grpcServer.isShutdown()) {
                appendToClientLog("Starting gRPC server");
                logger.log(INFO, "starting grpc server from main activity!!!!!!!");
                grpcServer.startServer(this.transportPaths);
                appendToClientLog("server started");
            }
        }
    }

    private void stopRpcServer() {
        synchronized (grpcServer) {
            if (!grpcServer.isShutdown()) {
                appendToClientLog("Shutting down gRPC server");
                grpcServer.shutdownServer();
            }
        }
    }

    @Override
    public void onBundleExchangeEvent(BundleExchangeServiceImpl.BundleExchangeEvent exchangeEvent) {
        appendToClientLog("File service event: " + exchangeEvent);
    }

    private void appendToClientLog(String message) {
        var intent = new Intent(getApplicationContext(), TransportWifiDirectFragment.class);
        intent.setAction(NET_DISCDD_BUNDLETRANSPORT_CLIENT_LOG_ACTION);
        intent.putExtra("message", message);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    private void broadcastWifiEvent(WifiDirectManager.WifiDirectEvent event) {
        //var intent = new Intent(getApplicationContext(), TransportWifiDirectFragment.class);
        var intent = new Intent(NET_DISCDD_BUNDLETRANSPORT_WIFI_EVENT_ACTION);
        intent.putExtra("type", event.type());
        intent.putExtra("message", event.message());
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    public CompletableFuture<WifiP2pDevice> requestDeviceInfo() {
        return wifiDirectManager.requestDeviceInfo();
    }

    public String getDeviceName() {
        return wifiDirectManager.getDeviceName();
    }

    public WifiDirectManager.WifiDirectStatus getStatus() {
        return wifiDirectManager.getStatus();
    }

//    public WifiP2pGroup getGroupInfo() {
//        return wifiDirectManager.getGroupInfo();
//    }

    public class TransportWifiDirectServiceBinder extends Binder {
        TransportWifiDirectService getService() {
            return TransportWifiDirectService.this;
        }
    }

}