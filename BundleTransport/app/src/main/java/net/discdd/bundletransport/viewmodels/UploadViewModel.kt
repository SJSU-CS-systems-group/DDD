package net.discdd.bundletransport.viewmodels

import android.app.Application
import android.content.Context.MODE_PRIVATE
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.discdd.pathutils.TransportPaths

data class ServerState(
    val domain: String = "",
    val port: String = "",
    val message: String? = "",
    val clientCount: String = "0",
    val serverCount: String = "0"
)

class UploadViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val sharedPref = context.getSharedPreferences("server_endpoint", MODE_PRIVATE)
    private val transportPaths: TransportPaths ?= null
    private val _state = MutableStateFlow(ServerState())
    val state = _state.asStateFlow()

    init {
        restoreDomainPort()
        _state.update {
        }
    }

    fun connectServer() {

    }

    fun reloadCount() {
        if (transportPaths != null && transportPaths.toClientPath != null && transportPaths.toServerPath != null) {
            
        } else {

        }
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

    fun restoreDomainPort() {
        _state.update { it.copy(
            domain = sharedPref.getString("domain", "") ?: "",
            port = sharedPref.getInt("port", 0).toString()
        ) }
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
}