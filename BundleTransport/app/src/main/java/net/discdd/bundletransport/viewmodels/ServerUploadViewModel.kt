package net.discdd.bundletransport.viewmodels

import android.app.Application
import android.content.Context.MODE_PRIVATE
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.discdd.pathutils.TransportPaths
import net.discdd.bundletransport.R
import net.discdd.transport.GrpcSecurityHolder
import net.discdd.transport.TransportToBundleServerManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger
import java.util.Base64;

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
    private val context get() = getApplication<Application>()
    private val sharedPref = context.getSharedPreferences("server_endpoint", MODE_PRIVATE)
    private val logger = Logger.getLogger(ServerUploadViewModel::class.java.name)
    private val _state = MutableStateFlow(ServerState())
    private val executor: ExecutorService = Executors.newFixedThreadPool(2);
    private var transportPaths: TransportPaths = TransportPaths(context.getExternalFilesDir(null)?.toPath())
    private var transportID: String ?= ""
    private val transportGrpcSecurity = GrpcSecurityHolder.setGrpcSecurityHolder(transportPaths.grpcSecurityPath);
    val state = _state.asStateFlow()

    init {
        transportID = Base64.getEncoder().encodeToString(
            transportGrpcSecurity.grpcKeyPair.public.encoded
        )
        restoreDomainPort()
        reloadCount()
    }

    fun connectServer() {
        viewModelScope.launch {
            if (!state.value.domain.isEmpty() && !state.value.port.isEmpty()) {
                _state.update { it.copy(message = context.getString(R.string.enter_the_domain_and_port)) }
                logger.log(Level.INFO, "Sending to " + state.value.domain + ":" + state.value.port + "...\n")

                try {
                    _state.update {
                        it.copy(message = "Initiating server exchange to " + state.value.domain + ":" + state.value.port + "...\n")
                    }

                    var transportToBundleServerManager : TransportToBundleServerManager
                        = TransportToBundleServerManager(transportPaths, state.value.domain, state.value.port,
                        {x: Void -> serverConnectComplete()},
                        {e: Exception -> serverConnectionError(e, state.value.domain + ":" + state.value.port)})
                    executor.execute(transportToBundleServerManager)
                    //withContext(Dispatchers.Main) run on UI thread?
                } catch (e : Exception) {
                    _state.update { it.copy(message = context.getString(R.string.bundles_upload_failed)) }
                }
            }
        }
    }

    fun serverConnectComplete(): Void? {
        _state.update { it.copy(message = "Server exchange complete.\n") }
        return null
    }

    fun serverConnectionError(e: Exception, transportTarget: String): Void? {
        _state.update { it.copy(message = "Server exchange incomplete with error.\n" +
                "Error: " + e.message + "\n") }
        toast(transportTarget)
        return null
    }

    fun reloadCount() {
        if (transportPaths != null && transportPaths!!.toClientPath != null && transportPaths!!.toServerPath != null) {
            val clientFiles: Array<String>? = transportPaths!!.toClientPath.toFile().list()
            val serverFiles: Array<String>? = transportPaths!!.toServerPath.toFile().list()

            val clientCountFiles = if (clientFiles != null) clientFiles.size else 0
            val serverCountFiles = if (serverFiles != null) serverFiles.size else 0

            _state.update { current -> current.copy(clientCount = clientCountFiles.toString()) }
            _state.update { current -> current.copy(serverCount = serverCountFiles.toString()) }
        } else {
            logger.warning("transportPaths or its paths are null when attempting to reload counts");
        }
    }

    fun saveDomainPort() {
        viewModelScope.launch {
            sharedPref
                .edit()
                .putString("domain", state.value.domain)
                .putInt("port", state.value.port.toInt())
                .apply()
            _state.update { it.copy(message = context.getString(R.string.saved)) }
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

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }
}