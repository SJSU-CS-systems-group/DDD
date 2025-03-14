package net.discdd.bundletransport.viewmodels

import android.app.Application
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.discdd.bundletransport.R
import net.discdd.bundletransport.ServerUploadFragment

data class ServerState(
    val domain: String = "",
    val port: String = "",
    val message: String = ""
)

class UploadViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val sharedPref = context.getSharedPreferences("server_endpoint", MODE_PRIVATE)
    private val _state = MutableStateFlow(ServerState())
    val state = _state.asStateFlow()

    fun onCreate() {

    }

    fun connectServer() {

    }

    fun saveDomainPort() {

    }

    fun restoreDomainPort() {

    }

    fun reloadCount() {

    }

    fun onDomainChanged(domain: String) {

    }

    fun onPortChanged(port: String) {
        /*
        viewModelScope.launch {
            sharedPref
                .edit()
                .putString("domain", state.value.domain)
                .putInt("port", state.value.port.toInt())
                .apply()
            _state.update { it.copy(message = context.getString(net.discdd.bundleclient)) }
        }

         */
    }
}