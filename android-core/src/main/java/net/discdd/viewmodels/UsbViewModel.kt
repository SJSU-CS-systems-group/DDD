package net.discdd.viewmodels

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.storage.StorageManager
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.discdd.UsbConnectionManager
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
    private val usbDirName = "ddd_transport"
    protected val logger: Logger = Logger.getLogger(UsbViewModel::class.java.name)
    protected val storageManager by lazy {
        application.getSystemService(Context.STORAGE_SERVICE) as StorageManager
    }

    private val _state = MutableStateFlow(UsbState())
    val state = _state.asStateFlow()
    var usbDirectory: DocumentFile? = null

    // this is used to signal the activity to prompt the user for directory access
    private val _requestDirectoryAccess = MutableLiveData<Unit>()
    val requestDirectoryAccess: LiveData<Unit> get() = _requestDirectoryAccess

    init {
        viewModelScope.launch {
            UsbConnectionManager.usbConnected.collect { isConnected ->
                if (isConnected) {
                    promptForDirectoryAccess()
                } else {
                    usbDirectory = null
                    _state.update {
                        it.copy(dddDirectoryExists = false)
                    }
                }
            }
        }
    }

    protected fun updateMessage(message: String?, color: Int) {
        _state.update {
            it.copy(showMessage = message, messageColor = color)
        }
    }

    fun promptForDirectoryAccess() {
        _requestDirectoryAccess.value = Unit
    }


    fun openedURI(context: Context, uri: Uri?) {
        val treeDocFile = uri?.let {
            DocumentFile.fromTreeUri(context, it)
        }
        if (treeDocFile == null) {
            usbDirectory = null
            return
        }
        val files = treeDocFile.listFiles()
        val dddDataDoc = treeDocFile.findFile("ddd_data.txt")
        val dddDirDoc = treeDocFile.findFile(usbDirName)
        if (dddDataDoc != null) {
            usbDirectory = dddDataDoc
            updateMessage("USB DDD_transport directory found!", Color.GREEN)
        } else if (dddDirDoc != null) {
            usbDirectory = dddDirDoc
            updateMessage("USB DDD_transport directory found", Color.GREEN)
        } else {
            usbDirectory = null
            updateMessage("USB DDD_transport directory not found!", Color.RED)
        }
        _state.update {
            it.copy(dddDirectoryExists = usbDirectory != null)
        }

    }
}
