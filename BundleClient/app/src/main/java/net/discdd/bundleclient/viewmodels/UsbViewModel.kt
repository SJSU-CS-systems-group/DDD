package net.discdd.bundleclient.viewmodels

import android.content.Context
import android.app.Application
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.discdd.bundleclient.WifiServiceManager
import java.io.File
import java.util.concurrent.Executors
import java.util.logging.Level.INFO
import java.util.logging.Logger

data class UsbState(
    val usbConnected: Boolean = false,
    val usbDirectory: File? = null,
    val usbExchangeButtonEnabled: Boolean = false,
    val usbConnectionText: String = "",
)

class UsbViewModel(
    application: Application
): AndroidViewModel(application) {
    companion object {
        const val usbDirName = "/DDD_transport"
    }
    private val logger = Logger.getLogger(UsbViewModel::class.java.name)
    private val storageManger by lazy {
        application.getSystemService(Context.STORAGE_SERVICE) as StorageManager
    }
    private val bundleTransmission by lazy {
        val wifiBgService = WifiServiceManager.getService()
        wifiBgService?.bundleTransmission
    }
    private val scheduledExecutor = Executors.newSingleThreadScheduledExecutor()
    private val _state = MutableStateFlow(UsbState())
    val state = _state.asStateFlow()

    private fun handleExchange() {
        val hasPermission = isManageAllFilesAccessGranted()
    }

    private fun usbDirExists(): Boolean {
        val storageVolumeList: MutableList<StorageVolume> = storageManger.getStorageVolumes()
        for (storageVolume in storageVolumeList) {
            if (storageVolume.isRemovable) {
                val usbDirectory = File((storageVolume.directory?.path + usbDirName))
                if (usbDirectory.exists()) {
                    logger.log(INFO, "DDD_transport directory exists.")
                    return true
                }
            }
        }
        logger.log(INFO,"DDD_transport directory does not exist.")
        return false
    }

    private fun isManageAllFilesAccessGranted(): Boolean {
        return Environment.isExternalStorageManager()
    }
}