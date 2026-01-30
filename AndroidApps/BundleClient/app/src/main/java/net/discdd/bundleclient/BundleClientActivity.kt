package net.discdd.bundleclient

import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import android.os.storage.StorageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.discdd.UsbConnectionManager
import net.discdd.bundleclient.screens.HomeScreen
import net.discdd.bundleclient.service.BundleClientService
import net.discdd.bundleclient.viewmodels.ClientUsbViewModel
import net.discdd.screens.LogFragment
import net.discdd.theme.ComposableTheme
import java.util.logging.Level.WARNING
import java.util.logging.Logger

class BundleClientActivity : ComponentActivity() {
    private lateinit var serviceIntent: Intent
    private val logger = Logger.getLogger(BundleClientActivity::class.java.name)
    private val connectivityManager: ConnectivityManager by lazy {
        getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private val sharedPreferences by lazy {
        getSharedPreferences(BundleClientService.NET_DISCDD_BUNDLECLIENT_SETTINGS, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (BuildConfig.DEBUG) {
            StrictMode.setVmPolicy(
                VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build()
            )
        }
        LogFragment.registerLoggerHandler()

        var usbViewModel: ClientUsbViewModel
        usbViewModel = ViewModelProvider(this).get(ClientUsbViewModel::class.java)
        usbViewModel.setRoot(applicationContext.filesDir)
        val openDocumentTreeLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    usbViewModel.openedURI(applicationContext, result.data?.data)
                    logger.info("Selected URI: ${result.data?.data}")
                } else {
                    logger.warning("Directory selection canceled")
                }
            }

        usbViewModel.requestDirectoryAccess.observe(this) {
            val storageManager = getSystemService(STORAGE_SERVICE) as StorageManager
            val volumes = storageManager.storageVolumes
            val usbVolume = volumes.find { it.isRemovable && it.state == "mounted" }
            usbVolume?.createOpenDocumentTreeIntent()?.apply {
                openDocumentTreeLauncher.launch(this)
            }
        }

        UsbConnectionManager.initialize(applicationContext)
        WifiServiceManager.initializeConnection(this)

        lifecycleScope.launch {
            try {
                serviceIntent = Intent(this@BundleClientActivity, BundleClientService::class.java)
                applicationContext.startForegroundService(serviceIntent)
            } catch (e: Exception) {
                logger.log(WARNING, "Failed to start BundleWifiDirectService", e)
            }

            try {
                val wifiDirectIntent = Intent(this@BundleClientActivity, BundleClientService::class.java)
                bindService(wifiDirectIntent, WifiServiceManager.getConnection(), BIND_AUTO_CREATE)
            } catch (e: Exception) {
                logger.log(WARNING, "Failed to bind to BundleWifiDirectService", e)
            }
        }

        setContent {
            ComposableTheme {
                val shouldShowBanner = remember {
                    !isK9Installed()
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    if (shouldShowBanner) {
                        K9IsMissingBanner(message = getString(R.string.k9_missing_banner_message))
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        HomeScreen()
                    }
                }
            }
        }
    }

    private fun isK9Installed(): Boolean {
        return try {
            packageManager.getPackageInfo("net.discdd.mail", 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    @Composable
    private fun K9IsMissingBanner(message: String) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFFF3CD)) // Light yellow warning color
                .padding(vertical = 12.dp, horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = message,
                color = Color(0xFF664D03), // Darker text for contrast
                textAlign = TextAlign.Center
            )
        }
    }

    override fun onResume() {
        super.onResume()
        var network = connectivityManager.activeNetwork
        if (network != null) {
            // check if wifi is valid when activity resumes
            WifiServiceManager.getService()?.getNetworkCallback()?.onAvailable(network)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        if (BundleClientService.getBackgroundExchangeSetting(sharedPreferences) <= 0) {
            stopService(serviceIntent)
        }

        UsbConnectionManager.cleanup(applicationContext)

        WifiServiceManager.clearService()
        unbindService(WifiServiceManager.getConnection())
    }
}
