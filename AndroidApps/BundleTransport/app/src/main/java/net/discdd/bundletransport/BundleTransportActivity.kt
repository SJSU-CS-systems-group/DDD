package net.discdd.bundletransport

import android.content.Intent
import android.os.Bundle
import android.os.storage.StorageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import net.discdd.UsbConnectionManager
import net.discdd.bundletransport.screens.TransportHomeScreen
import net.discdd.bundletransport.viewmodels.TransportUsbViewModel
import net.discdd.pathutils.TransportPaths
import net.discdd.screens.LogFragment
import net.discdd.theme.ComposableTheme
import java.util.logging.Logger

class BundleTransportActivity : ComponentActivity() {
    private var serviceIntent: Intent? = null
    private val logger = Logger.getLogger(BundleTransportActivity::class.java.name)
    private val sharedPreferences by lazy {
        getSharedPreferences(BundleTransportService.BUNDLETRANSPORT_PREFERENCES, MODE_PRIVATE)
    }
    private val transportPaths by lazy {
        TransportPaths(applicationContext.filesDir.toPath())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        LogFragment.registerLoggerHandler()

        var usbViewModel: TransportUsbViewModel = ViewModelProvider(this)[TransportUsbViewModel::class.java]
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
        ConnectivityManager.initialize(applicationContext)

        try {
            serviceIntent = Intent(this, BundleTransportService::class.java)
            applicationContext.startForegroundService(serviceIntent)
            bindService(serviceIntent!!, TransportServiceManager.connection, BIND_AUTO_CREATE)
        } catch (e: Exception) {
            logger.warning("Failed to start BundleTransportService")
        }

        setContent {
            ComposableTheme {
                TransportHomeScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ConnectivityManager.registerNetworkCallback()
    }

    override fun onPause() {
        super.onPause()
        ConnectivityManager.unregisterNetworkCallback()
    }

    override fun onDestroy() {
        super.onDestroy()

        if (sharedPreferences.getInt(BundleTransportService.BUNDLETRANSPORT_PERIODIC_PREFERENCE, 0) <= 0 &&
            serviceIntent != null
        ) {
            stopService(serviceIntent)
        }

        UsbConnectionManager.cleanup(applicationContext)
        ConnectivityManager.cleanup()
        TransportServiceManager.clearService()
        unbindService(TransportServiceManager.connection)
    }
}
