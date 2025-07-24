package net.discdd.bundletransport;

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
import android.os.Binder;
import android.os.IBinder;
import android.os.StrictMode;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import net.discdd.bundlerouting.service.BundleExchangeServiceImpl;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.bundletransport.wifi.DDDWifiServer;
import net.discdd.pathutils.TransportPaths;
import net.discdd.screens.LogFragment;
import net.discdd.tls.GrpcSecurityKey;
import net.discdd.transport.TransportToBundleServerManager;
import net.discdd.utils.DDDFixedRateScheduler;
import net.discdd.utils.UserLogRepository;
import org.acra.BuildConfig;
import org.bouncycastle.operator.OperatorCreationException;

import java.io.File;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.cert.CertificateException;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.String.format;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static net.discdd.AndroidAppConstants.BUNDLE_SERVER_DOMAIN;
import static net.discdd.AndroidAppConstants.BUNDLE_SERVER_PORT;

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
    public GrpcSecurityKey grpcKeys;
    String host;
    int port;
    ConnectivityManager connectivityManager;
    private TransportPaths transportPaths;
    private DDDFixedRateScheduler<String> periodicExchangeScheduler;
    final private SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener =
            (sharedPreferences, key) -> {
                switch (key) {
                    case BUNDLETRANSPORT_HOST_PREFERENCE -> host = sharedPreferences.getString(key, "");
                    case BUNDLETRANSPORT_PORT_PREFERENCE -> port = sharedPreferences.getInt(key, 0);
                    case BUNDLETRANSPORT_PERIODIC_PREFERENCE ->
                            periodicExchangeScheduler.setPeriodInMinutes(sharedPreferences.getInt(key, 0));
                }
            };
    private FileHttpServer httpServer;
    private boolean httpServerRunning = false;
    private DDDWifiServer dddWifiServer;

    public Future<String> queueServerExchangeNow() {
        return periodicExchangeScheduler.callItNow();
    }

    private String doServerExchange() {
        var activeNetwork = connectivityManager.getActiveNetwork();
        var caps = activeNetwork == null ? null : connectivityManager.getNetworkCapabilities(activeNetwork);
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return "No internet connection available, skipping server exchange.";
        }

        logExchange(INFO, "Starting server exchange with host: " + host + ", port: " + port);
        String message;
        try {
            var exchangeCounts = new TransportToBundleServerManager(grpcKeys,
                                                                    transportPaths,
                                                                    host,
                                                                    Integer.toString(port)).doExchange();
            message = format("deleted %d bundles, sent %d/%d, received %d/%d",
                             exchangeCounts.deleteCount,
                             exchangeCounts.uploadCount,
                             exchangeCounts.toUploadCount,
                             exchangeCounts.downloadCount,
                             exchangeCounts.toDownloadCount);
            logExchange(INFO, message);
        } catch (Exception e) {
            message = "Error during server exchange: " + e.getMessage();
            logExchange(SEVERE, message);
        }
        return message;
    }

    public static void logWifi(Level level, String message) {
        UserLogRepository.INSTANCE.log(UserLogRepository.UserLogType.WIFI, message, System.currentTimeMillis(), level);
    }

    static void logExchange(Level level, String message) {
        UserLogRepository.INSTANCE.log(UserLogRepository.UserLogType.EXCHANGE,
                                       message,
                                       System.currentTimeMillis(),
                                       level);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build());
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        this.transportPaths = new TransportPaths(getApplicationContext().getExternalFilesDir(null).toPath());
        try {
            this.grpcKeys = new GrpcSecurityKey(transportPaths.grpcSecurityPath, SecurityUtils.SERVER);
        } catch (InvalidAlgorithmParameterException | NoSuchAlgorithmException | NoSuchProviderException |
                 CertificateException | OperatorCreationException | IOException e) {
            logger.log(SEVERE, "Failed to initialize GrpcSecurity for SERVER", e);
            logExchange(SEVERE, "Failed to initialize GrpcSecurity for SERVER: " + e.getMessage());
        }
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        // BundleTransportService doesn't use LogFragment directly, but we do want our
        // logs to go to its logger
        LogFragment.registerLoggerHandler();
        SharedPreferences sharedPreferences = getSharedPreferences(BUNDLETRANSPORT_PREFERENCES, Context.MODE_PRIVATE);
        host = sharedPreferences.getString(BUNDLETRANSPORT_HOST_PREFERENCE, BUNDLE_SERVER_DOMAIN);
        port = sharedPreferences.getInt(BUNDLETRANSPORT_PORT_PREFERENCE, BUNDLE_SERVER_PORT);
        periodicExchangeScheduler = new DDDFixedRateScheduler<>(getApplicationContext(), this::doServerExchange);
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

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
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
            dddWifiServer = null;
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
                logExchange(INFO, "HTTP file server started on port 8080");
            } catch (IOException e) {
                logger.log(SEVERE, "Failed to start HTTP server", e);
                logExchange(SEVERE, "Failed to start HTTP server: " + e.getMessage());
            }
        }
    }

    private void stopHttpServer() {
        if (httpServerRunning && httpServer != null) {
            httpServer.stop();
            httpServerRunning = false;
            logExchange(INFO, "HTTP file server stopped");
        }
    }

    private void startRpcServer() {
        synchronized (grpcServer) {
            if (grpcServer.isShutdown()) {
                logExchange(INFO, "Starting gRPC server");
                logger.log(INFO, "starting grpc server");
                grpcServer.startServer(this.transportPaths);
                logExchange(INFO, "server started");
            }
        }
    }

    private void stopRpcServer() {
        synchronized (grpcServer) {
            if (!grpcServer.isShutdown()) {
                logExchange(INFO, "Stopping gRPC server");
                logger.log(INFO, "stopping gRPC server");
                grpcServer.shutdownServer();
                logExchange(INFO, "server stopped");
            }
        }
    }

    @Override
    public void onBundleExchangeEvent(BundleExchangeServiceImpl.BundleExchangeEvent exchangeEvent) {
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