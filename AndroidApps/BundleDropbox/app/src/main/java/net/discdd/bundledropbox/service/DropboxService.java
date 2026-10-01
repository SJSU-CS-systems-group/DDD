package net.discdd.bundledropbox.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import net.discdd.bundledropbox.R;

import java.util.logging.Logger;

/**
 * Foreground service that will own the dropbox runtime: identity and keys ({@code DropboxIdentity}),
 * the {@code RelayStore}, the gRPC server for clients, the Wi-Fi Direct radio driver, and the
 * {@code RoleCycleController} that oscillates between HOST and VISIT modes.
 *
 * <p>SCAFFOLD PLACEHOLDER: only the foreground-service skeleton (notification channel, startForeground
 * with type dataSync, START_STICKY, binder) adapted from {@code BundleTransportService}. The integrator
 * fills in the wiring; heavy initialization must run off the main thread, as in the transport.
 */
public class DropboxService extends Service {
    private static final Logger logger = Logger.getLogger(DropboxService.class.getName());

    /** Notification channel id (the transport uses "DDD-Transport"). */
    public static final String NOTIFICATION_CHANNEL_ID = "DDD-Dropbox";
    private static final int NOTIFICATION_ID = 1;

    private final IBinder binder = new DropboxBinder();

    @Override
    public void onCreate() {
        super.onCreate();
        logger.info("DropboxService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        startInForeground();
        // The dropbox is a stationary, always-plugged-in relay: restart it if the system kills it.
        return START_STICKY;
    }

    /**
     * Posts the persistent notification and promotes the service to the foreground with type dataSync
     * (manifest declares FOREGROUND_SERVICE + FOREGROUND_SERVICE_DATA_SYNC; Research/R5 §3).
     */
    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                                                              getString(R.string.dropbox_notification_channel_name),
                                                              NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(channel);
        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(R.string.dropbox_notification_title))
                .setContentText(getString(R.string.dropbox_notification_text))
                .setSmallIcon(R.drawable.bundledropbox_icon)
                .setOngoing(true)
                .build();
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        logger.info("DropboxService destroyed");
        super.onDestroy();
    }

    /** Local binder handed to {@code DropboxServiceManager} (mirrors the transport's service binder). */
    public class DropboxBinder extends Binder {
        /** @return the running service instance */
        public DropboxService getService() {
            return DropboxService.this;
        }
    }
}
