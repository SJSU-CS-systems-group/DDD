package net.discdd.bundletransport.viewmodels

import android.app.Application
import android.content.Context.MODE_PRIVATE
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.discdd.AndroidAppConstants
import net.discdd.bundletransport.R
import net.discdd.bundletransport.TransportServiceManager
import net.discdd.pathutils.TransportPaths
import java.util.logging.Logger
import androidx.core.content.edit
import net.discdd.bundletransport.BundleTransportService

data class ServerState(
        val domain: String = "",
        val port: String = "",
        val message: String? = "",
        val clientCount: String = "0",
        val serverCount: String = "0"
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
    private val context get() = getApplication<Application>()
    private val sharedPref by lazy { context.getSharedPreferences("server_endpoint", MODE_PRIVATE) }
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
    }

    fun connectServer() {
        val exchangeFuture = TransportServiceManager.getService()?.queueServerExchangeNow()
        viewModelScope.launch(Dispatchers.IO) {
            exchangeFuture?.get()
            reloadCount()
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
                        putString("domain", state.value.domain)
                                .putInt("port", state.value.port.toInt())
                    }
            _state.update { it.copy(message = context.getString(R.string.saved)) }
        }
    }

    fun restoreDomainPort() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update {
                it.copy(
                        domain = sharedPref.getString("domain", "") ?: "",
                        port = sharedPref.getInt("port", 0).toString()
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