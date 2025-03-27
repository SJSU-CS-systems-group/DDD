package net.discdd.bundleclient.viewmodels

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.os.Environment
import android.os.storage.StorageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.discdd.bundleclient.UsbConnectionManager
import net.discdd.bundleclient.WifiServiceManager
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.logging.Level
import java.util.logging.Level.INFO
import java.util.logging.Logger


data class UsbState(
    val filePermissionGranted: Boolean = false,
    val dddDirectoryExists: Boolean = false,
    val showMessage: String? = null, // null means no message
    val messageColor: Int = Color.BLACK,
)

class UsbViewModel(
    application: Application
): AndroidViewModel(application) {
    private val usbDirName = "/DDD_transport"
    private val logger = Logger.getLogger(UsbViewModel::class.java.name)
    private val storageManager by lazy {
        application.getSystemService(Context.STORAGE_SERVICE) as StorageManager
    }
    private val bundleTransmission by lazy {
        val wifiBgService = WifiServiceManager.getService()
        wifiBgService?.bundleTransmission
    }

    private var usbDirectory: File? = null

    private val _state = MutableStateFlow(UsbState())
    val state = _state.asStateFlow()

    init {
        refreshFilePermission()

        viewModelScope.launch {
            UsbConnectionManager.usbConnected.collect { isConnected ->
                if (isConnected) {
                    checkDddDirExists()
                } else {
                    _state.update { it.copy(dddDirectoryExists = false) }
                }
            }
        }
    }

    fun transferBundleToUsb() {
        if (_state.value.dddDirectoryExists && bundleTransmission != null) {
            viewModelScope.launch {
                try {
                    logger.log(Level.INFO, "Starting bundle creation...")
                    val bundleDTO = bundleTransmission!!.generateBundleForTransmission()
                    val bundleFile = bundleDTO.bundle.source
                    val targetFile = File(usbDirectory, bundleFile.name)

                    Files.copy(
                        bundleFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )

                    logger.log(Level.INFO, "Bundle creation and transfer successful")
                    _state.update {
                        it.copy(
                            showMessage = "Bundle created and transferred to USB",
                            messageColor = Color.GREEN
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    _state.update {
                        it.copy(
                            showMessage = "Error creating or transferring bundle: ${e.message}",
                            messageColor = Color.RED
                        )
                    }
                }
            }
        } else {
            _state.update {
                it.copy(
                    showMessage = "Cannot transfer bundle: USB not ready or directory not found",
                    messageColor = Color.RED
                )
            }
        }
    }

    fun checkDddDirExists() {
        val dddDirectoryExists = findDddDirectory()
        _state.update {
            it.copy(dddDirectoryExists = dddDirectoryExists)
        }
    }

    fun findDddDirectory(): Boolean {
        val storageVolumeList = storageManager.storageVolumes
        for (storageVolume in storageVolumeList) {
            if (storageVolume.isRemovable) {
                usbDirectory = File((storageVolume.directory?.path + usbDirName))
                if (usbDirectory?.exists() == true) {
                    logger.log(INFO, "DDD_transport directory exists at: ${usbDirectory?.absolutePath}")
                    return true
                }
            }
        }
        logger.log(INFO, "DDD_transport directory does not exist.")
        return false
    }

    fun refreshFilePermission() {
        val isGranted = isFileAccessGranted()
        _state.update {
            it.copy(
                filePermissionGranted = isGranted
            )
        }

        if (isGranted) {
            checkDddDirExists()
        }
    }

    private fun isFileAccessGranted(): Boolean {
        return Environment.isExternalStorageManager()
    }

    fun clearMessage() {
        _state.update {
            it.copy(showMessage = null)
        }
    }
}
