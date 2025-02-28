package net.discdd.bundleclient.viewmodels

import android.app.Application
import android.content.Context.MODE_PRIVATE
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.discdd.bundleclient.BundleClientWifiDirectService
import net.discdd.viewmodels.ConnectivityViewModel
import java.util.logging.Level
import java.util.logging.Logger

data class ServerState(
    val domain: String = "",
    val port: String = "",
    val message: String? = null,
)

class ServerViewModel(
    application: Application,
): AndroidViewModel(application) {
    private val logger = Logger.getLogger(ConnectivityViewModel::class.java.name)
    private val context get() = getApplication<Application>()
    private val sharedPref = context.getSharedPreferences(BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_SETTINGS, MODE_PRIVATE)
    private lateinit var wifiBgService: BundleClientWifiDirectService
    private val _state = MutableStateFlow(ServerState())
    val state = _state.asStateFlow()

    init {
        restoreDomainPort()
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

    fun connectServer() {
        viewModelScope.launch {
            try {
                _state.update {
                    it.copy(message = "Connecting to server...")
                }
                wifiBgService?.let { service ->
                    service.initiateServerExchange()
                        .thenAccept { bec ->
                            _state.update {
                                it.copy(message = "Upload status: ${bec.uploadStatus()}, Download status: ${bec.downloadStatus()}")
                            }
                        }
                } ?: run {
                    _state.update {
                        it.copy(message = "Error: Service is not available.")
                    }
                }
            } catch (e : Exception) {
                logger.log(Level.WARNING, "Failed to connect to server", e)
            }
        }
    }


    private fun restoreDomainPort() {
        _state.update { it.copy(
            domain = sharedPref.getString("domain", "") ?: "",
            port = sharedPref.getInt("port", 0).toString()
        ) }
    }

    fun saveDomainPort() {
        viewModelScope.launch {
            sharedPref
                .edit()
                .putString("domain", state.value.domain)
                .putInt("port", state.value.port.toInt())
                .apply()
            _state.update { it.copy(message = "Settings saved") }
        }
    }

    fun setWifiService(service: BundleClientWifiDirectService) {
        this.wifiBgService = service
    }
}