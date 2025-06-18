package net.discdd.bundletransport

import android.content.Context
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
import net.discdd.transport.GrpcSecurityHolder
import java.util.logging.Logger

class BundleTransportActivity : ComponentActivity() {
    private val logger = Logger.getLogger(BundleTransportActivity::class.java.name)
    private val sharedPreferences by lazy {
        getSharedPreferences(TransportWifiDirectService.WIFI_DIRECT_PREFERENCES, MODE_PRIVATE)
    }
    private val transportPaths by lazy {
        TransportPaths(applicationContext.filesDir.toPath())
    }

    private lateinit var usbViewModel: TransportUsbViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        LogFragment.registerLoggerHandler()

        usbViewModel = ViewModelProvider(this).get(TransportUsbViewModel::class.java)
        val openDocumentTreeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                usbViewModel.openedURI(applicationContext, result.data?.data)
                logger.info("Selected URI: ${result.data?.data}")
            } else {
                logger.warning("Directory selection canceled")
            }
        }

        usbViewModel.requestDirectoryAccess.observe(this) {
            val storageManager = getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val volumes = storageManager.storageVolumes
            val usbVolume = volumes.find { it.isRemovable && it.state == "mounted"}
            usbVolume?.createOpenDocumentTreeIntent()?.apply {
                openDocumentTreeLauncher.launch(this)
            }
        }

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

        if (!sharedPreferences.getBoolean(TransportWifiDirectService.WIFI_DIRECT_PREFERENCE_BG_SERVICE, true)) {
            stopService(Intent(this, TransportWifiDirectService::class.java))
        }

        UsbConnectionManager.cleanup(applicationContext)
        ConnectivityManager.cleanup()
        TransportWifiServiceManager.clearService()
        unbindService(TransportWifiServiceManager.getConnection())
    }
}