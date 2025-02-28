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

import net.discdd.bundleclient.R
import net.discdd.bundleclient.WifiServiceManager

data class ServerState(
    val domain: String = "",
    val port: String = "",
    val message: String? = null,
)

class ServerViewModel(
    application: Application,
): AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val sharedPref = context.getSharedPreferences(BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_SETTINGS, MODE_PRIVATE)
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
                    it.copy(message = context.getString(R.string.connecting_to_server))
                }
                val wifiBgService = WifiServiceManager.getService()
                wifiBgService?.let { service ->
                    service.initiateServerExchange()
                        .thenAccept { bec ->
                            _state.update { it.copy(message = context.getString(
                                R.string.upload_status,
                                bec.uploadStatus(),
                                bec.downloadStatus()
                            )) }
                        }
                } ?: run {
                    _state.update { it.copy(message = context.getString(R.string.service_not_available)) }
                }
            } catch (e : Exception) {
                _state.update { it.copy(message = context.getString(R.string.service_not_available)) }
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
            _state.update { it.copy(message = context.getString(R.string.settings_saved)) }
        }
    }
}