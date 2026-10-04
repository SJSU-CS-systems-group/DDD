package net.discdd.bundledropbox.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import net.discdd.bundledropbox.DropboxPrefs;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Brings the dropbox back after a reboot or an app update. The dropbox is a stationary, always-plugged-in
 * relay that cycles unattended (02 Decisions); {@code START_STICKY} only covers the process being killed, so
 * without this a power cut, an OS update or an app update would leave it down until an operator opened the
 * app (audit m14). The decision is {@link RelayPolicy#shouldAutoStart}: the "Start automatically" setting
 * (default on) and not stopped by the operator.
 *
 * <p>Limits:
 * <ul>
 *   <li>BOOT_COMPLETED arrives only after the first unlock. The store, keys and settings live in
 *       credential-encrypted storage, so a phone with a secure lock screen must be unlocked once after a
 *       reboot (or use no secure lock screen); see {@link RelayPolicy#shouldAutoStart} for why
 *       LOCKED_BOOT_COMPLETED is not used.</li>
 *   <li>Starting a {@code dataSync} foreground service from BOOT_COMPLETED is allowed at targetSdk 34 (Android
 *       15 forbids it for apps targeting 35+; see {@code DropboxService#startInForeground}, audit i8).</li>
 *   <li>The service re-checks Wi-Fi Direct permissions, so a phone whose permissions were revoked just sits in
 *       UNAVAILABLE.</li>
 * </ul>
 */
public final class BootReceiver extends BroadcastReceiver {
    private static final Logger logger = Logger.getLogger(BootReceiver.class.getName());

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        SharedPreferences prefs = DropboxPrefs.get(context);
        boolean startOnBoot = prefs.getBoolean(DropboxPrefs.KEY_START_ON_BOOT, DropboxPrefs.DEFAULT_START_ON_BOOT);
        boolean stopped = DropboxPrefs.isRelayStopped(prefs);
        if (!RelayPolicy.shouldAutoStart(action, startOnBoot, stopped)) {
            logger.info(action + ": dropbox not started (start automatically=" + startOnBoot +
                                ", stopped by operator=" + stopped + ")");
            return;
        }
        try {
            context.startForegroundService(new Intent(context, DropboxService.class));
            logger.info(action + ": dropbox service started");
        } catch (RuntimeException e) {
            // e.g. ForegroundServiceStartNotAllowedException on a platform that restricts these starts.
            logger.log(Level.WARNING, "Could not start the dropbox after " + action, e);
        }
    }
}
