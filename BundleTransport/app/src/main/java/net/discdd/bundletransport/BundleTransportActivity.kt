package net.discdd.bundletransport

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import net.discdd.UsbConnectionManager
import net.discdd.bundletransport.screens.TransportHomeScreen
import net.discdd.pathutils.TransportPaths
import net.discdd.screens.LogFragment
import net.discdd.theme.ComposableTheme
import net.discdd.transport.GrpcSecurityHolder
import java.util.logging.Logger

class BundleTransportActivity: ComponentActivity() {
    private val logger = Logger.getLogger(BundleTransportActivity::class.java.name)
    private val sharedPreferences by lazy {
        getSharedPreferences(TransportWifiDirectService.WIFI_DIRECT_PREFERENCES, MODE_PRIVATE)
    }
    private val transportPaths by lazy {
        TransportPaths(applicationContext.filesDir.toPath())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        LogFragment.registerLoggerHandler()
        UsbConnectionManager.initialize(applicationContext)
        ConnectivityManager.initialize(applicationContext)
        TransportWifiServiceManager.initialize(this)

        try {
            val intent = Intent(this, TransportWifiDirectService::class.java)
            applicationContext.startForegroundService(intent)
            bindService(intent, TransportWifiServiceManager.getConnection(), BIND_AUTO_CREATE)
        } catch (e: Exception) {
            logger.warning("Failed to start TransportWifiDirectService")
        }

        try {
            GrpcSecurityHolder.setGrpcSecurityHolder(transportPaths.grpcSecurityPath)
        } catch (e: Exception) {
            logger.severe("Failed to initialize GrpcSecurity for transport")
        }

        setContent {
            ComposableTheme {
                TransportHomeScreen()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        ConnectivityManager.unregisterNetworkCallback()
    }

    override fun onDestroy() {
        super.onDestroy()

        if (!sharedPreferences.getBoolean(TransportWifiDirectService.WIFI_DIRECT_PREFERENCE_BG_SERVICE, true)) {
            stopService(Intent(this, TransportWifiDirectService::class.java))
        }

        UsbConnectionManager.cleanup(applicationContext)
        ConnectivityManager.cleanup()
        TransportWifiServiceManager.clearService()
        unbindService(TransportWifiServiceManager.getConnection())
    }
}