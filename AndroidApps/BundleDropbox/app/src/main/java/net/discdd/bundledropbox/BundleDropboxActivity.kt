package net.discdd.bundledropbox

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import net.discdd.bundledropbox.screens.DropboxHomeScreen
import net.discdd.bundledropbox.service.DropboxService
import net.discdd.screens.LogFragment
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
 * must keep cycling after the UI is closed.
 */
class BundleDropboxActivity : ComponentActivity() {
    private val logger = Logger.getLogger(BundleDropboxActivity::class.java.name)
    private var bound = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        LogFragment.registerLoggerHandler()

        try {
            val serviceIntent = Intent(this, DropboxService::class.java)
            applicationContext.startForegroundService(serviceIntent)
            bound = bindService(serviceIntent, DropboxServiceManager.connection, BIND_AUTO_CREATE)
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
        DropboxServiceManager.clearService()
        if (bound) {
            unbindService(DropboxServiceManager.connection)
            bound = false
        }
    }
}
