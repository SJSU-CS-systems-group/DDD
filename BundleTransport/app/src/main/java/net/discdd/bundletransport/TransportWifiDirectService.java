package net.discdd.bundletransport;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.os.Binder;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.discdd.android.fragments.LogFragment;
import net.discdd.bundlerouting.service.BundleExchangeServiceImpl;
import net.discdd.pathutils.TransportPaths;
import net.discdd.wifidirect.WifiDirectManager;
import net.discdd.wifidirect.WifiDirectStateListener;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

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

        wifiDirectManager = new WifiDirectManager(this, this, true);
        wifiDirectManager.initialize();
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
        switch (action.type()) {
            case WIFI_DIRECT_MANAGER_GROUP_INFO_CHANGED:
                var groupInfo = wifiDirectManager.getGroupInfo();
                appendToClientLog("Group info: " + (groupInfo == null ? "N/A" :
                        groupInfo.getClientList().stream().map(d -> d.deviceName).collect(Collectors.joining(", "))));
                if (groupInfo == null || groupInfo.getClientList().isEmpty()) {
                    appendToClientLog("No clients connected. Shutting down gRPC server");
                    stopRpcServer();
                } else {
                    appendToClientLog(String.format("%d clients connected. Starting gRPC server",
                                                    groupInfo.getClientList().size()));
                    try {
                        startRpcServer();
                    } catch (Exception e) {
                        logger.log(SEVERE, "Failed to start gRPC server", e);
                    }
                }
        }
        broadcastWifiEvent(action);
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

    public WifiP2pGroup getGroupInfo() {
        return wifiDirectManager.getGroupInfo();
    }

    public class TransportWifiDirectServiceBinder extends Binder {
        TransportWifiDirectService getService() {
            return TransportWifiDirectService.this;
        }
    }

}