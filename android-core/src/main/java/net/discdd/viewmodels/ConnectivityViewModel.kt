package net.discdd.viewmodels

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.logging.Level
import java.util.logging.Level.INFO
import java.util.logging.Logger

data class ConnectivityState(
    val networkConnected: Boolean = false,
    val isConnectBtnEnabled: Boolean = false
)

class ConnectivityViewModel(
    application: Application
): AndroidViewModel(application) {
    private val logger = Logger.getLogger(ConnectivityViewModel::class.java.name)
    private val context get() = getApplication<Application>()
    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private val _state = MutableStateFlow(ConnectivityState())
    val state = _state.asStateFlow()

    init {
        createAndRegisterConnectivityManager()
    }

    private fun createAndRegisterConnectivityManager() {
        viewModelScope.launch {
            logger.log(INFO, "Registering connectivity manager")
            val networkRequest: NetworkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()

            val serverConnectNetworkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    logger.log(INFO, "Available network: $network")
                    _state.update { it.copy(networkConnected = true) }
                }

                override fun onLost(network: Network) {
                    logger.log(Level.WARNING, "Lost network connectivity")
                    _state.update { it.copy(networkConnected = false) }
                }

                override fun onUnavailable() {
                    logger.log(Level.WARNING, "Unavailable network connectivity")
                    _state.update { it.copy(networkConnected = false) }
                }

                override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                    logger.log(Level.WARNING, "Blocked network connectivity")
                }
            }

            connectivityManager.registerNetworkCallback(networkRequest, serverConnectNetworkCallback)
        }
    }
}