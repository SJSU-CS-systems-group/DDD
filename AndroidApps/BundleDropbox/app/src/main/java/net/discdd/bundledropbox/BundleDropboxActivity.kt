package net.discdd.bundledropbox

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import net.discdd.bundledropbox.screens.DropboxHomeScreen
import net.discdd.bundledropbox.service.DropboxService
import net.discdd.bundledropbox.service.UiLogBridge
import net.discdd.theme.ComposableTheme
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Launcher activity of DDD Dropbox, mirroring `BundleTransportActivity`: register the JUL handler for
 * the Logs tab, start the foreground [DropboxService] and bind to it through [DropboxServiceManager],
 * then show [DropboxHomeScreen].
 *
 * Differences from the transport: no USB, connectivity or server-upload wiring (the dropbox is
 * offline), and the service is never stopped in [onDestroy]; the dropbox is a stationary relay that
 * must keep cycling after the UI is closed. Only the operator's Stop button stops it, and while it is
 * stopped opening the app only binds (the Status tab offers Start).
 *
 * `launchMode="singleTop"` (manifest) plus the notification's SINGLE_TOP|CLEAR_TOP flags keep a single
 * instance; the binding is still reference-counted in [DropboxServiceManager] (audit m11).
 */
class BundleDropboxActivity : ComponentActivity() {
    private val logger = Logger.getLogger(BundleDropboxActivity::class.java.name)
    private var bound = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Thread-safe wrapper around android-core's Logs-tab handler (audit M4); idempotent.
        UiLogBridge.install()

        try {
            val serviceIntent = Intent(this, DropboxService::class.java)
            // A stopped relay stays stopped until the operator presses Start (the service also enforces it).
            if (!DropboxPrefs.isRelayStopped(DropboxPrefs.get(this))) {
                applicationContext.startForegroundService(serviceIntent)
            }
            bound = bindService(serviceIntent, DropboxServiceManager.connection, BIND_AUTO_CREATE)
            if (bound) DropboxServiceManager.onActivityBound()
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to start DropboxService", e)
        }

        setContent {
            ComposableTheme {
                DropboxHomeScreen()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unbind only; DropboxService keeps running as a started foreground service.
        if (bound) {
            DropboxServiceManager.onActivityUnbound()
            unbindService(DropboxServiceManager.connection)
            bound = false
        }
    }
}
