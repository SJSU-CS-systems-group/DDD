package net.discdd.bundleclient.viewmodels

import android.app.Application
import android.content.Context.MODE_PRIVATE
import androidx.compose.runtime.MutableState
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.discdd.AndroidAppConstants
import net.discdd.bundleclient.service.BundleClientService
import net.discdd.bundleclient.R
import net.discdd.bundleclient.WifiServiceManager

data class ServerState(
    val domain: String = "",
    val port: String = "",
    val message: String = "",
)

class ServerViewModel(
    application: Application,
): AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val sharedPref = context.getSharedPreferences(BundleClientService.NET_DISCDD_BUNDLECLIENT_SETTINGS, MODE_PRIVATE)
    private val _state = MutableStateFlow(ServerState())
    val state = _state.asStateFlow()

    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting = _isTransmitting.asStateFlow()

    init {
        AndroidAppConstants.checkDefaultDomainPortSettings(sharedPref)
        restoreDomainPort()
    }

    fun onDomainChanged(domain: String) {
        _state.update { current -> current.copy(domain = domain) }
    }

    fun onPortChanged(port: String) {
        _state.update { current -> current.copy(port = port) }
    }

    fun clearMessage() {
        viewModelScope.launch { _state.update { it.copy(message = "") } }
    }

    private fun appendMessage(message: String) {
        _state.update { current ->
            val currentLines = current.message.split("\n").takeLast(10)
            val newLines = (currentLines + message)
            current.copy(message = newLines.joinToString("\n"))
        }
    }

    fun connectServer() {
        _isTransmitting.value = true
        viewModelScope.launch {
            try {
                appendMessage(context.getString(R.string.connecting_to_server))
                val wifiBgService = WifiServiceManager.getService()
                wifiBgService?.let { service ->
                    service.initiateServerExchange()
                        .thenAccept { bec ->
                            {
                                appendMessage(
                                    context.getString(
                                        R.string.upload_status,
                                        bec.uploadStatus(),
                                        bec.downloadStatus()
                                    )
                                )
                                _isTransmitting.value = false
                            }}
                        } ?: run {
                    appendMessage(context.getString(R.string.service_not_available))
                    _isTransmitting.value = false
                }
            } catch (e : Exception) {
                _isTransmitting.value = false
                appendMessage(context.getString(R.string.service_not_available))
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
            appendMessage(context.getString(R.string.settings_saved))
        }
    }
}