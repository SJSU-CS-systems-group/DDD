package net.discdd.bundletransport;

import android.content.Context;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

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

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.discdd.bundlerouting.service.BundleExchangeServiceImpl;
import net.discdd.bundletransport.viewmodels.WifiDirectViewModel;
import net.discdd.client.bundletransmission.BundleTransmission;
import net.discdd.pathutils.TransportPaths;
import net.discdd.screens.LogFragment;
import net.discdd.wifidirect.WifiDirectManager;
import net.discdd.wifidirect.WifiDirectStateListener;

import java.io.File;
import java.io.IOException;
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
    private NotificationManager notificationManager;
    private FileHttpServer httpServer;
    private boolean httpServerRunning = false;

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
            NotificationChannel channel = new NotificationChannel("DDD-Transport",
                                                                  "DDD Bundle Transport",
                                                                  NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("DDD Transport Service");

            notificationManager = getSystemService(NotificationManager.class);
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
        startHttpServer();
    }

    @Override
    public void onDestroy() {
        if (wifiDirectManager != null) {
            wifiDirectManager.shutdown();
        }
        stopHttpServer();
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
                appendToClientLog("Group info: " + (groupInfo == null ?
                                                    "N/A" :
                                                    groupInfo.getClientList()
                                                            .stream()
                                                            .map(d -> d.deviceName)
                                                            .collect(Collectors.joining(", "))));
                if (groupInfo == null || groupInfo.getClientList().isEmpty()) {
                    if (notificationManager != null) {
                        notificationManager.cancel(1001);
                    }

                    // TODO: Disabled shutdown for now as it seems to cause problems with clients reconnecting
                    //       I'm not sure we need to, it probably uses very little resources when idle.
                    // appendToClientLog("No clients connected. Shutting down gRPC server");
                    // stopRpcServer();
                } else {
                    appendToClientLog(String.format("%d clients connected. Starting gRPC server",
                                                    groupInfo.getClientList().size()));
                    startRpcServer();

                    NotificationChannel channel = new NotificationChannel("DDD-Exchange",
                                                                          "DDD Bundle Transport",
                                                                          NotificationManager.IMPORTANCE_HIGH);
                    channel.setDescription("Initiating Bundle Exchange...");

                    NotificationCompat.Builder builder = new NotificationCompat.Builder(this,
                                                                                        "DDD-Transport").setSmallIcon(R.drawable.bundletransport_icon)
                            .setContentTitle(getString(R.string.exchanging_with_client))
                            .setContentText(getString(R.string.initiating_bundle_exchange))
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setAutoCancel(false)
                            .setOngoing(true);

                    notificationManager.notify(1001, builder.build());
                }
        }
        broadcastWifiEvent(action);
    }

    private void startHttpServer() {
        if (!httpServerRunning) {
            try {
                File filesDir = getApplicationContext().getExternalFilesDir(null);
                httpServer = new FileHttpServer(8080, filesDir);
                httpServer.start();
                httpServerRunning = true;
                appendToClientLog("HTTP file server started on port 8080");
            } catch (IOException e) {
                logger.log(SEVERE, "Failed to start HTTP server", e);
                appendToClientLog("Failed to start HTTP server: " + e.getMessage());
            }
        }
    }

    private void stopHttpServer() {
        if (httpServerRunning && httpServer != null) {
            httpServer.stop();
            httpServerRunning = false;
            appendToClientLog("HTTP file server stopped");
        }
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
        var intent = new Intent(getApplicationContext(), WifiDirectViewModel.class);
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