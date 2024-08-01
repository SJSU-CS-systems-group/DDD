package net.discdd.bundletransport;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.p2p.WifiP2pGroup;
import android.os.Binder;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import net.discdd.bundlerouting.service.FileServiceImpl;
import net.discdd.wifidirect.WifiDirectManager;
import net.discdd.wifidirect.WifiDirectStateListener;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
        implements WifiDirectStateListener, RpcServerStateListener,
        FileServiceImpl.FileServiceEventListener {
    public static final String NET_DISCDD_BUNDLETRANSPORT_CLIENT_LOG_ACTION =
            "net.discdd.bundletransport.CLIENT_LOG";
    public static final String NET_DISCDD_BUNDLETRANSPORT_WIFI_EVENT_ACTION =
            "net.discdd.bundletransport.WIFI_EVENT";
    private static final Logger logger =
            Logger.getLogger(TransportWifiDirectService.class.getName());
    private final IBinder binder = new TransportWifiDirectServiceBinder();
    RpcServer grpcServer = new RpcServer(this);
    private WifiDirectManager wifiDirectManager;

    public TransportWifiDirectService() {
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int rc = super.onStartCommand(intent, flags, startId);
        logger.log(INFO, "Starting " + TransportWifiDirectService.class.getName() + " with flags " +
                flags + " and startId " + startId);
        startForeground();
        logger.log(INFO, "Started " + TransportWifiDirectService.class.getName() + " with flags " +
                flags + " and startId " + startId);
        return START_STICKY;
    }

    private void startForeground() {
        try {
            NotificationChannel channel =
                    new NotificationChannel("DDD-Transport", "DDD Bundle Transport",
                                            NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("DDD Transport Service");

            var notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);

            Notification notification =
                    new NotificationCompat.Builder(this, "DDD-Transport").setContentTitle(
                            "DDD Bundle Transport").build();
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC |
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            ServiceCompat.startForeground(this, 1, notification, type);
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to start foreground service", e);
        }

        wifiDirectManager = new WifiDirectManager(this, null, this, "BundleTransport", true);
        wifiDirectManager.initialize();
        wifiDirectManager.createGroup();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public CompletableFuture<Boolean> removeGroup() {
        return wifiDirectManager.removeGroup();
    }

    public void setDeviceName(String deviceName) {
        wifiDirectManager.setDeviceName(deviceName);
    }

    public void createGroup() {
        wifiDirectManager.createGroup();
    }

    public CompletableFuture<WifiP2pGroup> requestGroupInfo() {
        return wifiDirectManager.requestGroupInfo();
    }

    public CompletionStage<Boolean> requestP2PState() {
        return wifiDirectManager.requestP2pState();
    }

    @Override
    public void onReceiveAction(WifiDirectManager.WifiDirectEvent action) {
        switch (action.type()) {
            case WIFI_DIRECT_MANAGER_FORMED_CONNECTION_FAILED -> {
                startRpcServer();
            }
            case WIFI_DIRECT_MANAGER_FORMED_CONNECTION_SUCCESSFUL -> startRpcServer();
        }
        broadcastWifiEvent(action);
    }

    private void startRpcServer() {
        synchronized (grpcServer) {
            if (grpcServer.isShutdown()) {
                appendToClientLog("Starting gRPC server");
                logger.log(INFO, "starting grpc server from main activity!!!!!!!");
                grpcServer.startServer(getApplicationContext(), this);
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
    public void onStateChanged(RpcServer.ServerState newState) {
    }

    @Override
    public void onFileServiceEvent(FileServiceImpl.FileServiceEvent fileServiceEvent) {
        appendToClientLog("File service event: " + fileServiceEvent);
    }

    private void appendToClientLog(String message) {
        var intent = new Intent(getApplicationContext(), TransportWifiDirectFragment.class);
        intent.setAction(NET_DISCDD_BUNDLETRANSPORT_CLIENT_LOG_ACTION);
        intent.putExtra("message", message);
        sendBroadcast(intent);
    }

    private void broadcastWifiEvent(WifiDirectManager.WifiDirectEvent event) {
        var intent = new Intent(getApplicationContext(), TransportWifiDirectFragment.class);
        intent.setAction(NET_DISCDD_BUNDLETRANSPORT_WIFI_EVENT_ACTION);
        intent.putExtra("type", event.type());
        intent.putExtra("message", event.message());
        sendBroadcast(intent);
    }

    public class TransportWifiDirectServiceBinder extends Binder {
        TransportWifiDirectService getService() {
            return TransportWifiDirectService.this;
        }
    }
}