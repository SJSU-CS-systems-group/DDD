package net.discdd.bundleclient

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import net.discdd.bundleclient.screens.HomeScreen
import net.discdd.screens.LogFragment
import net.discdd.theme.ComposableTheme
import java.util.concurrent.CompletableFuture
import java.util.logging.Level.WARNING
import java.util.logging.Logger

class MainActivity: ComponentActivity() {
    private val logger = Logger.getLogger(MainActivity::class.java.name)
    private val connectivityManager: ConnectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private val sharedPreferences by lazy {
        getSharedPreferences(BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_SETTINGS, MODE_PRIVATE)
    }
    val serviceReady = CompletableFuture<MainActivity>()
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BundleClientWifiDirectService.BundleClientWifiDirectServiceBinder
            WifiServiceManager.setService(binder.service)
            serviceReady.complete(this@MainActivity)
        }
        override fun onServiceDisconnected(arg0: ComponentName) {
            WifiServiceManager.clearService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            applicationContext.startForegroundService(Intent(this, BundleClientWifiDirectService::class.java))
        } catch (e: Exception) {
            logger.log(WARNING, "Failed to start TransportWifiDirectService")
        }
        val intent = Intent(this, BundleClientWifiDirectService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        LogFragment.registerLoggerHandler()

        setContent {
            ComposableTheme {
                HomeScreen()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        if (!sharedPreferences.getBoolean(
            BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_SETTING_BACKGROUND_EXCHANGE, false
        )) {
            stopService(Intent(this, BundleClientWifiDirectService::class.java))
        }
        WifiServiceManager.clearService()
        unbindService(connection)
    }
}