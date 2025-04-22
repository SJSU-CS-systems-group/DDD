package net.discdd.viewmodels

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
import net.discdd.UsbConnectionManager
import java.io.File
import java.util.logging.Level.INFO
import java.util.logging.Logger

data class UsbState(
        val filePermissionGranted: Boolean = false,
        val dddDirectoryExists: Boolean = false,
        val showMessage: String? = null, // null means no message
        val messageColor: Int = Color.BLACK,
)

open class UsbViewModel(
        application: Application
) : AndroidViewModel(application) {
    private val usbDirName = "/DDD_transport"
    protected val logger: Logger = Logger.getLogger(UsbViewModel::class.java.name)
    protected val storageManager by lazy {
        application.getSystemService(Context.STORAGE_SERVICE) as StorageManager
    }

    private val _state = MutableStateFlow(UsbState())
    val state = _state.asStateFlow()
    var usbDirectory: File? = null

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

    protected fun updateMessage(message: String?, color: Int) {
        _state.update {
            it.copy(showMessage = message, messageColor = color)
        }
    }

    fun checkDddDirExists() {
        val dddDirectoryExists = findDddDirectory()
        _state.update {
            it.copy(dddDirectoryExists = dddDirectoryExists)
        }
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

    fun clearMessage() {
        _state.update {
            it.copy(showMessage = null)
        }
    }

    private fun isFileAccessGranted(): Boolean {
        return Environment.isExternalStorageManager()
    }

    private fun findDddDirectory(): Boolean {
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
}
