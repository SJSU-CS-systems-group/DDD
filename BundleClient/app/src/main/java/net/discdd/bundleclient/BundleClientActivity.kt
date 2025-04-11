package net.discdd.bundleclient

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.discdd.bundleclient.screens.HomeScreen
import net.discdd.screens.LogFragment
import net.discdd.theme.ComposableTheme
import net.discdd.viewmodels.PermissionsViewModel
import java.util.logging.Level.WARNING
import java.util.logging.Logger

class BundleClientActivity: ComponentActivity() {
    private val logger = Logger.getLogger(BundleClientActivity::class.java.name)
    private val connectivityManager: ConnectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private val sharedPreferences by lazy {
        getSharedPreferences(BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_SETTINGS, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        LogFragment.registerLoggerHandler()

        UsbConnectionManager.initialize(applicationContext)
        WifiServiceManager.initializeConnection(this)
        WifiAwareManager.initializeConnection(this)

        lifecycleScope.launch {
            try {
                applicationContext.startForegroundService(
                    Intent(this@BundleClientActivity, BundleClientWifiDirectService::class.java)
                )
            } catch (e: Exception) {
                logger.log(WARNING, "Failed to start BundleWifiDirectService", e)
            }

            try {
                applicationContext.startForegroundService(
                    Intent(this@BundleClientActivity, BundleClientWifiAwareService::class.java)
                )
            } catch (e: Exception) {
                logger.log(WARNING, "Failed to start BundleWifiAwareService", e)
            }

            try {
                val wifiDirectIntent = Intent(this@BundleClientActivity, BundleClientWifiDirectService::class.java)
                bindService(wifiDirectIntent, WifiServiceManager.getConnection(), Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                logger.log(WARNING, "Failed to bind to BundleWifiDirectService", e)
            }

            try {
                val wifiAwareIntent = Intent(this@BundleClientActivity, BundleClientWifiAwareService::class.java)
                bindService(wifiAwareIntent, WifiAwareManager.getConnection(), Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                logger.log(WARNING, "Failed to bind to BundleWifiAwareService", e)
            }
        }

        val permissionsViewModel: PermissionsViewModel by viewModels()
        val activityResultLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results -> permissionsViewModel.handlePermissionResults(results) }

        setContent {
            ComposableTheme {
                HomeScreen(
                    permissionsViewModel = permissionsViewModel,
                    activityResultLauncher = activityResultLauncher,
                )
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

        stopService(Intent(this, BundleClientWifiAwareService::class.java))

        UsbConnectionManager.cleanup(applicationContext)

        WifiServiceManager.clearService()
        unbindService(WifiServiceManager.getConnection())

        WifiAwareManager.clearService()
        unbindService(WifiAwareManager.getConnection())
    }
}