package net.discdd.bundleclient.viewmodels

import android.app.Application
import android.content.Context.MODE_PRIVATE
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.discdd.AndroidAppConstants
import net.discdd.bundleclient.R
import net.discdd.bundleclient.WifiServiceManager
import net.discdd.bundleclient.service.BundleClientService
import net.discdd.client.bundlesecurity.ClientSecurity

data class ServerState(
    val domain: String = "",
    val port: String = "",
    val message: String = "",
)

class ServerViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val sharedPref = context.getSharedPreferences(
        BundleClientService.NET_DISCDD_BUNDLECLIENT_SETTINGS,
        MODE_PRIVATE
    )

    private val _state = MutableStateFlow(ServerState())
    val state = _state.asStateFlow()

    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting = _isTransmitting.asStateFlow()

    // Store the last saved values for comparison and reversion
    private var oldDomain: String = ""
    private var oldPort: Int = 0

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
            val currentLines = current.message.split("\n").takeLast(10).filter { it.isNotBlank() }
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
                            appendMessage(
                                context.getString(
                                    R.string.upload_status,
                                    bec.uploadStatus(),
                                    bec.downloadStatus()
                                )
                            )
                            _isTransmitting.value = false
                        }
                } ?: run {
                    appendMessage(context.getString(R.string.service_not_available))
                    _isTransmitting.value = false
                }
            } catch (e: Exception) {
                _isTransmitting.value = false
                appendMessage(context.getString(R.string.service_not_available))
            }
        }
    }

    private fun restoreDomainPort() {
        oldDomain = sharedPref.getString("domain", "") ?: ""
        oldPort = sharedPref.getInt("port", 0)

        val savedPort = if (oldPort > 0) oldPort.toString() else ""
        _state.update { it.copy(domain = oldDomain, port = savedPort) }
    }

    fun saveDomainPort() {
        viewModelScope.launch {
            val domain = state.value.domain.trim()
            val portStr = state.value.port.trim()
            val port = portStr.toIntOrNull()

            // Validate before saving
            if (domain.isEmpty()) {
                appendMessage(context.getString(R.string.invalid_domain))
                return@launch
            }
            if (port == null || port !in 1..65_535) {
                appendMessage(context.getString(R.string.invalid_port))
                return@launch
            }

            val domainChanged = oldDomain != domain
            val portChanged = oldPort != port

            if (domainChanged || portChanged) {
                sharedPref.edit()
                    .putString("domain", domain)
                    .putInt("port", port)
                    .apply()

                oldDomain = domain
                oldPort = port

                appendMessage(context.getString(R.string.settings_saved))
                appendMessage(context.getString(R.string.switching_server_will_reset_keys))
                ClientSecurity.resetInstance()
            } else {
                appendMessage(context.getString(R.string.no_changes_detected))
            }
        }
    }

    fun revertDomainPortChanges() {
        _state.update {
            it.copy(domain = oldDomain, port = if (oldPort > 0) oldPort.toString() else "")
        }
        appendMessage(context.getString(R.string.changes_reverted))
    }
}
