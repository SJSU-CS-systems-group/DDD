package net.discdd.bundletransport.viewmodels

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.os.storage.StorageManager
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.discdd.bundletransport.UsbFileManager
import net.discdd.pathutils.TransportPaths
import java.util.logging.Logger

data class TransportUsbState(
    val filePermissionGranted: Boolean = false,
    val showMessage: String? = null, // null means no message
    val messageColor: Int = Color.BLACK,
)

class TransportUsbViewModel(
    application: Application,
    transportPaths: TransportPaths,
): AndroidViewModel(application) {
    private val usbDirName = "/DDD_transport"
    private val logger = Logger.getLogger(TransportUsbViewModel::class.java.name)
    private val storageManager by lazy {
        application.getSystemService(Context.STORAGE_SERVICE) as StorageManager
    }
    private var usbFileManager: UsbFileManager = UsbFileManager(storageManager, transportPaths)

    private val _state = MutableStateFlow(TransportUsbState())
    val state = _state.asStateFlow()
}