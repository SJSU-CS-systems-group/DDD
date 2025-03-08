package net.discdd.bundletransport.viewmodels

import android.app.Application
import android.content.Context
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
    private val context get() = getApplication<Application>();
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

    }
}