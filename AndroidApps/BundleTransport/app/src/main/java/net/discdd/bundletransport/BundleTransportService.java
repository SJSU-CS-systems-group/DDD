package net.discdd.bundletransport;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.lifecycle.Observer;
import net.discdd.bundlerouting.service.BundleExchangeServiceImpl;
import net.discdd.bundletransport.service.DDDWifiServiceEvents;
import net.discdd.bundletransport.wifi.DDDWifiServer;
import net.discdd.pathutils.TransportPaths;
import net.discdd.screens.LogFragment;
import net.discdd.transport.TransportToBundleServerManager;
import net.discdd.util.DDDFixedRateScheduler;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

/**
 * This service handles the Wifi Direct group and the gRPC server.
 * It runs in the background and is designed to stay running even if the
 * app goes away.
 * Wifi Direct function calls from the App are passed through to this
 * service to handle using the TransportWifiDirectServiceBinder which
 * returns a reference to this service.
 */
public class BundleTransportService extends Service implements BundleExchangeServiceImpl.BundleExchangeEventListener {
    public static final String BUNDLETRANSPORT_PREFERENCES = "net.discdd.bundletransport";
    public static final String BUNDLETRANSPORT_HOST_PREFERENCE = "host";
    public static final String BUNDLETRANSPORT_PORT_PREFERENCE = "port";
    public static final String BUNDLETRANSPORT_PERIODIC_PREFERENCE = "periodicExchangeInMinutes";
    private static final Logger logger = Logger.getLogger(BundleTransportService.class.getName());
    private final IBinder binder = new TransportWifiDirectServiceBinder();
    private final RpcServer grpcServer = new RpcServer(this);
    String host;
    int port;
    private TransportPaths transportPaths;
    final DDDFixedRateScheduler<Void> periodicExchangeScheduler = new DDDFixedRateScheduler<>(this::doServerExchange);
    final private SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener =
            (sharedPreferences, key) -> {
                switch (key) {
                    case BUNDLETRANSPORT_HOST_PREFERENCE -> host = sharedPreferences.getString(key, "");
                    case BUNDLETRANSPORT_PORT_PREFERENCE -> port = sharedPreferences.getInt(key, 0);
                    case BUNDLETRANSPORT_PERIODIC_PREFERENCE ->
                            periodicExchangeScheduler.setPeriodInMinutes(sharedPreferences.getInt(key, 0));
                }
            };
    private NotificationManager notificationManager;
    private FileHttpServer httpServer;
    private boolean httpServerRunning = false;
    private DDDWifiServer dddWifiServer;
    final private Observer<? super DDDWifiServer.DDDWifiServerEvent> liveDataObserver = event -> {
        switch (event.type) {
            case DDDWIFISERVER_MESSAGE -> appendToClientLog(event.data);
            case DDDWIFISERVER_DEVICENAME_CHANGED -> broadcastWifiEvent(event);
            case DDDWIFISERVER_NETWORKINFO_CHANGED -> {
                if (dddWifiServer == null || dddWifiServer.getNetworkInfo() == null ||
                dddWifiServer.getNetworkInfo().inetAddress == null) {
                    if (notificationManager != null) {
                        notificationManager.cancel(1001);
                    }
                } else {
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
                broadcastWifiEvent(event);
            }
        }
    };

    private Void doServerExchange() throws Exception {
        // TODO: change TransportToBundleServerManager into a Callable to make this all cleaner
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (host == null || port == 0) {
            throw new NullPointerException("host and port has not been set");
        }
        new TransportToBundleServerManager(transportPaths, host, Integer.toString(port), v -> {
            result.complete(null);
            return null;
        }, e -> {
            result.completeExceptionally(e);
            return null;
        }).run();
        return result.get();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        this.transportPaths = new TransportPaths(getApplicationContext().getExternalFilesDir(null).toPath());
        super.onStartCommand(intent, flags, startId);
        // BundleTransportService doesn't use LogFragment directly, but we do want our
        // logs to go to its logger
        LogFragment.registerLoggerHandler();
        SharedPreferences sharedPreferences = getSharedPreferences(BUNDLETRANSPORT_PREFERENCES, Context.MODE_PRIVATE);
        host = sharedPreferences.getString(BUNDLETRANSPORT_HOST_PREFERENCE, "");
        port = sharedPreferences.getInt(BUNDLETRANSPORT_PORT_PREFERENCE, 0);
        periodicExchangeScheduler.setPeriodInMinutes(sharedPreferences.getInt(BUNDLETRANSPORT_PERIODIC_PREFERENCE, 0));
        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener);
        logger.log(INFO,
                   "Starting " + BundleTransportService.class.getName() + " with flags " + flags + " and startId " +
                           startId);
        startForeground();
        logger.log(INFO,
                   "Started " + BundleTransportService.class.getName() + " with flags " + flags + " and startId " +
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
        dddWifiServer = new DDDWifiServer(getApplicationContext());
        dddWifiServer.initialize();
        startHttpServer();
        startRpcServer();
    }

    @Override
    public void onDestroy() {
        if (dddWifiServer != null) {
            dddWifiServer.shutdown();
        }
        stopHttpServer();
        stopRpcServer();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
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
                appendToClientLog("Stopping gRPC server");
                logger.log(INFO, "stopping gRPC server");
                grpcServer.shutdownServer();
                appendToClientLog("server stopped");
            }
        }
    }

    @Override
    public void onBundleExchangeEvent(BundleExchangeServiceImpl.BundleExchangeEvent exchangeEvent) {
        appendToClientLog("File service event: " + exchangeEvent);
    }

    private void appendToClientLog(String message) {
        DDDWifiServiceEvents.sendEvent(new DDDWifiServer.DDDWifiServerEvent(DDDWifiServer.DDDWifiServerEventType.DDDWIFISERVER_MESSAGE,
                                                                            message));
    }

    private void broadcastWifiEvent(DDDWifiServer.DDDWifiServerEvent event) {
        DDDWifiServiceEvents.sendEvent(event);
    }

    public DDDWifiServer getDddWifiServer() {
        return dddWifiServer;
    }

    public void wifiPermissionGranted() {
        dddWifiServer.wifiPermissionGranted();
    }

    public class TransportWifiDirectServiceBinder extends Binder {
        BundleTransportService getService() {
            return BundleTransportService.this;
        }
    }
}