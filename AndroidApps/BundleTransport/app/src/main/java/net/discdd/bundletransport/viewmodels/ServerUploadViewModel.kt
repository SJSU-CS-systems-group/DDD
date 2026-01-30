package net.discdd.bundletransport.viewmodels

import android.app.Application
import android.content.Context.MODE_PRIVATE
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.discdd.AndroidAppConstants
import net.discdd.bundletransport.BundleTransportService
import net.discdd.bundletransport.R
import net.discdd.bundletransport.TransportServiceManager
import net.discdd.pathutils.TransportPaths
import java.io.File
import java.util.logging.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

sealed interface RecencyBlobStatus {
    data object Fresh : RecencyBlobStatus
    data object Missing : RecencyBlobStatus
    data class Outdated(val age: Duration) : RecencyBlobStatus
}

data class ServerState(
    val domain: String = "",
    val port: String = "",
    val message: String? = "",
    val clientCount: String = "0",
    val serverCount: String = "0",
    val recencyBlobStatus: RecencyBlobStatus = RecencyBlobStatus.Missing
)

class ServerUploadViewModel(
    application: Application
) : AndroidViewModel(application) {
    // this is a truncated version of the transport ID, which is used to identify the transport
    val transportID: String
        get() {
            val service = TransportServiceManager.getService()
            return service?.transportId ?: "Unknown"
        }
    private val RECENCY_BLOB_AGE_THRESHOLD = 24.hours
    private val context get() = getApplication<Application>()
    private val sharedPref by lazy {
        context.getSharedPreferences(
            BundleTransportService.BUNDLETRANSPORT_PREFERENCES,
            MODE_PRIVATE
        )
    }
    private val transportPrefs by lazy {
        context.getSharedPreferences(BundleTransportService.BUNDLETRANSPORT_PREFERENCES, MODE_PRIVATE)
    }

    private val logger = Logger.getLogger(ServerUploadViewModel::class.java.name)
    private val transportPaths: TransportPaths by lazy {
        TransportPaths(context.getExternalFilesDir(null)?.toPath())
    }
    private val _state = MutableStateFlow(ServerState())
    val state = _state.asStateFlow()
    private val _backgroundExchange = MutableStateFlow(0)
    val backgroundExchange = _backgroundExchange.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            AndroidAppConstants.checkDefaultDomainPortSettings(sharedPref)
            restoreDomainPort()
            reloadCount()
            _backgroundExchange.value = transportPrefs.getInt(
                BundleTransportService.BUNDLETRANSPORT_PERIODIC_PREFERENCE,
                0
            )
        }

        viewModelScope.launch {
            while (isActive) {
                updateRecencyBlobStatus()
                delay(30_000)
            }
        }
    }

    fun connectServer() {
        val exchangeFuture = TransportServiceManager.getService()?.queueServerExchangeNow()
        viewModelScope.launch(Dispatchers.IO) {
            exchangeFuture?.get()
            reloadCount()
            updateRecencyBlobStatus()
        }
    }

    fun updateRecencyBlobStatus() {
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                val clientDir = context.getExternalFilesDir("BundleTransmission/client")
                val file = File(clientDir, "recencyBlob.bin")

                if (!file.exists()) {
                    RecencyBlobStatus.Missing
                } else {
                    val fileLastModified = System.currentTimeMillis() - file.lastModified()
                    if (fileLastModified > RECENCY_BLOB_AGE_THRESHOLD.inWholeMilliseconds) {
                        RecencyBlobStatus.Outdated(fileLastModified.milliseconds)
                    } else {
                        RecencyBlobStatus.Fresh
                    }
                }
            }

            onRecencyBlobStatusChanged(status)
        }
    }

    fun reloadCount() {
        if (transportPaths != null && transportPaths.toClientPath != null && transportPaths.toServerPath != null) {
            val clientFiles: Array<String>? = transportPaths.toClientPath.toFile().list()
            val serverFiles: Array<String>? = transportPaths.toServerPath.toFile().list()

            val clientCountFiles = if (clientFiles != null) clientFiles.size else 0
            val serverCountFiles = if (serverFiles != null) serverFiles.size else 0

            _state.update { current -> current.copy(clientCount = clientCountFiles.toString()) }
            _state.update { current -> current.copy(serverCount = serverCountFiles.toString()) }
        } else {
            logger.warning("transportPaths or its paths are null when attempting to reload counts")
        }
    }

    fun saveDomainPort() {
        viewModelScope.launch(Dispatchers.IO) {
            sharedPref
                .edit {
                    putString(BundleTransportService.BUNDLETRANSPORT_DOMAIN_PREFERENCE, state.value.domain)
                    putInt(BundleTransportService.BUNDLETRANSPORT_PORT_PREFERENCE, state.value.port.toInt())
                }
            _state.update { it.copy(message = context.getString(R.string.saved)) }
        }
    }

    fun restoreDomainPort() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update {
                it.copy(
                    domain = sharedPref.getString(BundleTransportService.BUNDLETRANSPORT_DOMAIN_PREFERENCE, "")
                        ?: "",
                    port = sharedPref.getInt(BundleTransportService.BUNDLETRANSPORT_PORT_PREFERENCE, 0).toString()
                )
            }
        }
    }

    fun onDomainChanged(domain: String) {
        _state.update { current -> current.copy(domain = domain) }
    }

    fun onPortChanged(port: String) {
        _state.update { current -> current.copy(port = port) }
    }

    fun onRecencyBlobStatusChanged(recencyBlobStatus: RecencyBlobStatus) {
        _state.update { current -> current.copy(recencyBlobStatus = recencyBlobStatus) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun setBackgroundExchange(value: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            _backgroundExchange.value = value
            transportPrefs.edit { putInt(BundleTransportService.BUNDLETRANSPORT_PERIODIC_PREFERENCE, value) }
        }
    }
}
