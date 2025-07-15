package net.discdd.bundletransport.viewmodels

import android.app.Application
import android.content.Context.MODE_PRIVATE
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.discdd.AndroidAppConstants
import net.discdd.bundletransport.R
import net.discdd.bundletransport.TransportServiceManager
import net.discdd.pathutils.TransportPaths
import net.discdd.utils.UserLogRepository
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger

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
    public val transportID: String
        get() {
            val service = TransportServiceManager.getService()
            return service?.grpcKeys?.grpcKeyPair?.public?.encoded?.let {
                Base64.getEncoder().encodeToString(it).slice(4..21)
            } ?: "Unknown"
        }
    private val context get() = getApplication<Application>()
    private val sharedPref = context.getSharedPreferences("server_endpoint", MODE_PRIVATE)
    private val logger = Logger.getLogger(ServerUploadViewModel::class.java.name)
    private val _state = MutableStateFlow(ServerState())
    private val executor: ExecutorService = Executors.newFixedThreadPool(2);
    private var transportPaths: TransportPaths = TransportPaths(context.getExternalFilesDir(null)?.toPath())
    val state = _state.asStateFlow()

    init {
        AndroidAppConstants.checkDefaultDomainPortSettings(sharedPref)
        restoreDomainPort()
        reloadCount()
    }

    fun connectServer() {
        val exchangeFuture = TransportServiceManager.getService()?.queueServerExchangeNow()
        if (exchangeFuture == null) {
            UserLogRepository.log(UserLogRepository.UserLogType.EXCHANGE, "TransportService is not available.", level = Level.SEVERE)
        } else {
            viewModelScope.launch {
                try {
                    val message = exchangeFuture.get()
                    UserLogRepository.log(
                            UserLogRepository.UserLogType.EXCHANGE,
                            message
                    )
                    reloadCount()
                } catch (e: Exception) {
                    UserLogRepository.log(
                            UserLogRepository.UserLogType.EXCHANGE,
                            "Server connection error: ${e.message}",
                            level = Level.SEVERE
                    )
                }
            }
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
        _state.update {
            it.copy(
                    domain = sharedPref.getString("domain", "") ?: "",
                    port = sharedPref.getInt("port", 0).toString()
            )
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
}