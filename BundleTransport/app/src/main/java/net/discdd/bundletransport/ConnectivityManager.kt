package net.discdd.bundletransport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.logging.Level
import java.util.logging.Logger

object ConnectivityManager {
    private val logger = Logger.getLogger(ConnectivityManager::class.java.name)

    private val _internetAvailable = MutableStateFlow(false)
    val internetAvailable: StateFlow<Boolean> = _internetAvailable.asStateFlow()

    private var systemConnectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return

        systemConnectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        updateConnectionState()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                logger.log(Level.INFO, "Network available")
                _internetAvailable.value = true
            }

            override fun onLost(network: Network) {
                logger.log(Level.INFO, "Network unavailable")
                _internetAvailable.value = false
            }

            override fun onUnavailable() {
                logger.log(Level.INFO, "Network unavailable")
                _internetAvailable.value = false
            }
        }

        registerNetworkCallback()
        initialized = true
        logger.log(Level.INFO, "Connectivity Manager initialized. Internet available: ${_internetAvailable.value}")
    }

    fun registerNetworkCallback() {
        networkCallback?.let { callback ->
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            systemConnectivityManager?.registerNetworkCallback(networkRequest, callback)
        }
    }

    fun unregisterNetworkCallback() {
        networkCallback?.let { callback ->
            systemConnectivityManager?.unregisterNetworkCallback(callback)
        }
    }

    private fun updateConnectionState() {
        systemConnectivityManager?.let { manager ->
            _internetAvailable.value = manager.getNetworkCapabilities(manager.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
    }

    fun cleanup() {
        unregisterNetworkCallback()
        systemConnectivityManager = null
        networkCallback = null
        initialized = false
    }
}