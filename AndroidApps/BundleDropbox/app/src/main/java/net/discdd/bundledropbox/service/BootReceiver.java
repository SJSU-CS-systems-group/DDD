package net.discdd.bundledropbox.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import net.discdd.bundledropbox.DropboxPrefs;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Brings the dropbox back after a reboot. The dropbox is a stationary, always-plugged-in relay that cycles
 * unattended (02 Decisions); {@code START_STICKY} only covers the process being killed, so without this a
 * power cut or OS update would leave it down until an operator opened the app.
 *
 * <p>Gated by the {@link DropboxPrefs#KEY_START_ON_BOOT} setting (default on). Starting a {@code dataSync}
 * foreground service from {@code BOOT_COMPLETED} is allowed at targetSdk 34 (Android 15 restricts it for
 * apps targeting 35+; revisit this if targetSdk is raised). The service itself re-checks Wi-Fi Direct
 * permissions, so a phone whose permissions were revoked just sits in UNAVAILABLE.
 */
public final class BootReceiver extends BroadcastReceiver {
    private static final Logger logger = Logger.getLogger(BootReceiver.class.getName());

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        boolean enabled = DropboxPrefs.get(context)
                .getBoolean(DropboxPrefs.KEY_START_ON_BOOT, DropboxPrefs.DEFAULT_START_ON_BOOT);
        if (!enabled) {
            logger.info("Boot completed; start on boot is off, dropbox stays stopped");
            return;
        }
        try {
            context.startForegroundService(new Intent(context, DropboxService.class));
            logger.info("Boot completed; dropbox service started");
        } catch (RuntimeException e) {
            // e.g. ForegroundServiceStartNotAllowedException on a platform that restricts boot starts.
            logger.log(Level.WARNING, "Could not start the dropbox after boot", e);
        }
    }
}
