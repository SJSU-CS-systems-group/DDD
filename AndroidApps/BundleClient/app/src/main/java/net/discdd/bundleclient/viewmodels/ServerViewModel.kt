package net.discdd.bundleclient.viewmodels

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
import net.discdd.bundleclient.R
import net.discdd.bundleclient.WifiServiceManager
import net.discdd.bundleclient.service.BundleClientService
import net.discdd.bundlesecurity.DDDPEMEncoder
import net.discdd.bundlesecurity.SecurityUtils
import net.discdd.client.bundlesecurity.ClientSecurity
import net.discdd.utils.QRCodeParser
import java.nio.file.Files

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

    private val _isCustomServer = MutableStateFlow(sharedPref.getBoolean("custom_server_keys", false))
    val isCustomServer = _isCustomServer.asStateFlow()

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
                try {
                    ClientSecurity.resetInstance()
                }catch(e : Exception) {
                    revertDomainPortChanges()
                    appendMessage("Error Changing  Resetting ClientSecurity")
                }
            } else {
                appendMessage(context.getString(R.string.no_changes_detected))
            }
        }
    }

    fun applyScannedConfig(config: QRCodeParser.ServerConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Write public keys to the server key directory
                val dataDir = context.dataDir.toPath()
                val serverKeyDir = dataDir
                    .resolve(SecurityUtils.BUNDLE_SECURITY_DIR)
                    .resolve(SecurityUtils.SERVER_KEY_PATH)
                serverKeyDir.toFile().mkdirs()

                if (config.identity != null && config.signedPre != null && config.ratchet != null) {
                    writeKeyPem(serverKeyDir, SecurityUtils.SERVER_IDENTITY_KEY, config.identity)
                    writeKeyPem(serverKeyDir, SecurityUtils.SERVER_SIGNED_PRE_KEY, config.signedPre)
                    writeKeyPem(serverKeyDir, SecurityUtils.SERVER_RATCHET_KEY, config.ratchet)

                    // Mark that we have custom QR-scanned keys
                    sharedPref.edit()
                        .putBoolean("custom_server_keys", true)
                        .apply()
                }

                // Save host and port
                sharedPref.edit()
                    .putString("domain", config.host)
                    .putInt("port", config.port)
                    .apply()

                oldDomain = config.host
                oldPort = config.port
                _state.update { it.copy(domain = config.host, port = config.port.toString()) }

                // Delete old client keys and session so resetInstance() generates fresh ones
                val bundleSecDir = dataDir.resolve(SecurityUtils.BUNDLE_SECURITY_DIR)
                val clientKeyDir = bundleSecDir.resolve(SecurityUtils.CLIENT_KEY_PATH)
                clientKeyDir.toFile().deleteRecursively()
                val sessionStore = bundleSecDir.resolve(SecurityUtils.SESSION_STORE_FILE)
                sessionStore.toFile().delete()

                // Reinitialize the entire bundle transmission chain with new keys
                WifiServiceManager.getService()?.reinitializeBundleTransmission()

                _isCustomServer.value = true
                appendMessage("Saved. Host: ${config.host}, Port: ${config.port}")
            } catch (e: Exception) {
                appendMessage("Failed to apply scanned config: ${e.message}")
            }
        }
    }

    private fun writeKeyPem(dir: java.nio.file.Path, filename: String, urlBase64Key: String) {
        val pemContent = "${DDDPEMEncoder.PUB_KEY_HEADER}\n${urlBase64Key}\n${DDDPEMEncoder.PUB_KEY_FOOTER}"
        Files.write(dir.resolve(filename), pemContent.toByteArray())
    }

    fun revertDomainPortChanges() {
        _state.update {
            it.copy(domain = oldDomain, port = if (oldPort > 0) oldPort.toString() else "")
        }
        appendMessage(context.getString(R.string.changes_reverted))
    }
}
