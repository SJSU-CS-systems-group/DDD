package net.discdd.bundleclient.viewmodels

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentValues.TAG
import android.content.Context
import android.content.IntentFilter
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.ServiceDiscoveryInfo
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.discdd.bundleclient.BundleClientWifiAwareBroadcastReceiver
import net.discdd.bundleclient.BundleClientWifiAwareService
import net.discdd.bundleclient.WifiAwareManager
import net.discdd.wifiaware.WifiAwareHelper
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

data class WifiAwareState(
    val wifiAwareHelper: WifiAwareHelper? = null,
    val resultText: String = "",
    val subscriber: BundleClientWifiAwareService? = null,
    val peers: Set<ServiceDiscoveryInfo> = emptySet(),
    val wifiAwareInitialized: Boolean = false,
    val wifiAwareAvailable: Boolean = false,
)

class WifiAwareViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val bundleClientWifiAwareBroadcastReceiver =
        BundleClientWifiAwareBroadcastReceiver().apply {
            setViewModel(this@WifiAwareViewModel)
        }
    private val wifiAwareManager = WifiAwareManager
    private var _state = MutableStateFlow(WifiAwareState())
    val state = _state.asStateFlow()

    private val _messages = MutableStateFlow<List<WifiAwareHelper.PeerMessage>>(emptyList())

    fun addMessage(message: WifiAwareHelper.PeerMessage) {
        _messages.update { currentMessages -> currentMessages + message }
    }

    private var serviceType: String = "com.example.bundleclient"

    private val intentFilter = IntentFilter().apply {
        addAction(BundleClientWifiAwareService.NET_DISCDD_BUNDLECLIENT_WIFI_EVENT_ACTION)
        addAction(BundleClientWifiAwareService.NET_DISCDD_BUNDLECLIENT_LOG_ACTION)
    }

    init {
        serviceReadyFuture()
        registerBroadcastReceiver()
    }

    private fun serviceReadyFuture() {
        viewModelScope.launch {
            try {
                Log.d("WifiAwareViewModel", "Waiting for service to be ready")
                val service = withContext(Dispatchers.IO) {
                    wifiAwareManager.serviceReady.get() // Perform the blocking call in a background thread
                }
                initializeWifiAwareHelper(wifiAwareManager.serviceReady) // Call the method here
                onWifiAwareInitialized()
                Log.d("WifiAwareViewModel", "Service is ready: $service")
            } catch (e: Exception) {
                Log.e("WifiAwareViewModel", "Service initialization failed", e)
                onWifiAwareInitializationFailed()
            }
        }
    }

    fun onWifiAwareInitialized() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    wifiAwareInitialized = true,
                    wifiAwareAvailable = true
                )
            }
            appendResultText("WiFi Aware initialized successfully")
        }
    }

    fun onWifiAwareInitializationFailed() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    wifiAwareInitialized = false,
                    wifiAwareAvailable = false
                )
            }
            appendResultText("WiFi Aware initialization failed")
        }
    }

    fun onWifiAwareSessionTerminated() {
        viewModelScope.launch {
            // Stop any ongoing discovery
            stopDiscovery()

            _state.update {
                it.copy(
                    wifiAwareInitialized = false,
                    wifiAwareAvailable = false,
                    subscriber = null,
                    peers = emptySet()
                )
            }
            appendResultText("WiFi Aware session terminated")
        }
    }

    fun initializeWifiAwareHelper(serviceReadyFuture: CompletableFuture<BundleClientWifiAwareService>) {
        viewModelScope.launch {
            try {
                // Wait for the service to be ready
                serviceReadyFuture.thenAccept { service ->
                    // Access the WifiAwareHelper once the service is ready
                    val wifiAwareHelper = service.wifiAwareHelper

                    // Update state with WifiAwareHelper
                    _state.update { oldState ->
                        oldState.copy(
                            wifiAwareHelper = wifiAwareHelper, // Save WifiAwareHelper here
                            wifiAwareAvailable = wifiAwareHelper.isWifiAwareAvailable(),
                            wifiAwareInitialized = wifiAwareHelper.wifiAwareSession != null
                        )
                    }

                    Log.d("WifiAwareViewModel", "Wi-Fi Aware helper initialized successfully")
                }
            } catch (e: Exception) {
                // Handle initialization failure
                Log.e("WifiAwareViewModel", "Error initializing Wi-Fi Aware: ${e.message}")
                appendResultText("Error initializing Wi-Fi Aware: ${e.message}")
            }
        }
    }


    @SuppressLint("MissingPermission")
    fun startDiscovery(serviceType: String) {
        val wifiAwareHelper = _state.value.wifiAwareHelper
        if (wifiAwareHelper == null) {
            appendResultText("Wi-Fi Aware is not initialized.")
            return
        }

        this.serviceType = serviceType

        try {
            // Stop existing discovery if any
            _state.value.subscriber?.unsubscribe()

            // Create message, service discovery, and service lost receivers
            val messageReceiver = Consumer<WifiAwareHelper.PeerMessage> { message ->
                _messages.update { it + message }
            }

            val serviceDiscoveryReceiver = Consumer<ServiceDiscoveryInfo> { peer ->
                _state.update { s -> s.copy(peers = s.peers + peer) }
            }

            val serviceLostReceiver = Consumer<PeerHandle> { peer ->
                _state.update { s ->
                    s.copy(peers = s.peers.filterNot { it.peerHandle == peer }.toSet())
                }
            }

            // Start discovery
            val newSubscriber = BundleClientWifiAwareService.startDiscovery(
                wifiAwareHelper,
                serviceType,
                null, // serviceSpecificInfo
                null, // matchFilter
                messageReceiver,
                serviceDiscoveryReceiver,
                serviceLostReceiver
            )

            // Update state with new subscriber
            _state.update { oldState -> oldState.copy(subscriber = newSubscriber) }

            // Log success
            appendResultText("Started discovery for service: $serviceType")
        } catch (e: WifiAwareHelper.WiFiAwareException) {
            Log.e(TAG, "Failed to start discovery", e)
            appendResultText("Failed to start discovery: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during discovery", e)
            appendResultText("Unexpected error: ${e.message}")
        }
    }

    fun stopDiscovery() {
        _state.update { oldState ->
            oldState.subscriber?.unsubscribe()
            appendResultText("Stopped discovery")
            oldState.copy(subscriber = null)
        }
    }

    fun appendResultText(text: String?) {
        if (text == null) return

        viewModelScope.launch {
            _state.update { oldState ->
                var resultText = oldState.resultText

                // Limit result text to 20 lines
                if (resultText.count { it == '\n' } > 20) {
                    val nl = resultText.indexOf('\n')
                    if (nl != -1) {
                        resultText = resultText.substring(nl + 1)
                    }
                }

                // Append the new text
                resultText += "$text\n"
                oldState.copy(resultText = resultText)
            }
        }
    }

    @SuppressLint("ServiceCast")
    fun handleWifiAwareAvailabilityChange(context: Context) {
        val wifiAwareHelper = _state.value.wifiAwareHelper

        // If wifiAwareHelper is not available, log an error and return
        if (wifiAwareHelper == null) {
            Log.e(TAG, "Wi-Fi Aware helper is not initialized.")
            appendResultText("Wi-Fi Aware is not initialized.")
            return
        }

        // First, stop any existing discovery sessions
        stopDiscovery()

        // Check current availability and update state
        val isAvailable = try {
            wifiAwareHelper.isWifiAwareAvailable
        } catch (e: Exception) {
            Log.e(TAG, "Error checking WiFi Aware availability", e)
            false
        }

        // Update state based on availability
        _state.update { s ->
            s.copy(
                wifiAwareAvailable = isAvailable,
                wifiAwareInitialized = isAvailable && wifiAwareHelper.wifiAwareSession != null
            )
        }

        // Log availability change
        appendResultText("Wi-Fi Aware availability changed: ${if (isAvailable) "available" else "unavailable"}")

        // Optionally restart discovery if Wi-Fi Aware is available and no active session exists
        if (isAvailable && _state.value.subscriber == null) {
            viewModelScope.launch {
                // Wait a moment before restarting discovery
                kotlinx.coroutines.delay(1000)
                if (_state.value.wifiAwareInitialized) {
                    startDiscovery(serviceType)
                }
            }
        }
    }

    fun connectToTransport(peer: ServiceDiscoveryInfo): CompletableFuture<InetSocketAddress> {
        val subscriber = _state.value.subscriber
        if (subscriber == null) {
            val future = CompletableFuture<InetSocketAddress>()
            future.completeExceptionally(Exception("No active subscriber session"))
            appendResultText("Failed to connect: No active subscriber session")
            return future
        }

        try {
            appendResultText("Connecting to peer: ${peer.peerHandle}")
            return subscriber.connectToTransport(peer.peerHandle).also { future ->
                future.whenComplete { address, throwable ->
                    if (throwable != null) {
                        appendResultText("Connection failed: ${throwable.message}")
                    } else {
                        appendResultText("Connected to peer at: $address")
                    }
                }
            }
        } catch (e: Exception) {
            val future = CompletableFuture<InetSocketAddress>()
            future.completeExceptionally(e)
            appendResultText("Error connecting to peer: ${e.message}")
            return future
        }
    }

    override fun onCleared() {
        // Clean up when ViewModel is cleared
        stopDiscovery()
        try {
            val wifiAwareHelper = _state.value.wifiAwareHelper
            wifiAwareHelper?.unregisterWifiIntentReceiver()
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering WiFi intent receiver", e)
        }
        super.onCleared()
    }

    private fun registerBroadcastReceiver() {
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(bundleClientWifiAwareBroadcastReceiver, intentFilter)
    }
}

