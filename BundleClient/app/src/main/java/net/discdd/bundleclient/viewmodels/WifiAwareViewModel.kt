package net.discdd.bundleclient.viewmodels

import android.annotation.SuppressLint
import android.content.ContentValues.TAG
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.ServiceDiscoveryInfo
import android.util.Log
import androidx.lifecycle.ViewModel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.discdd.bundleclient.BundleClientWifiAwareSubscriber
import net.discdd.wifiaware.WifiAwareHelper
import java.util.function.Consumer

data class WifiAwareState(
    val subscriber: BundleClientWifiAwareSubscriber? = null,
    val peers: Set<ServiceDiscoveryInfo> = mutableSetOf(),
    val wifiAwareInitialized: Boolean = false,
    val wiFiAwareAvailable: Boolean = false,
)

class WifiAwareSubscriberViewModel(private val wifiAwareHelper: WifiAwareHelper) : ViewModel() {
    private var _state = MutableStateFlow(WifiAwareState())
    val state = _state.asStateFlow()
    private var _messages = MutableStateFlow<List<WifiAwareHelper.PeerMessage>>(mutableListOf())
    val messages = _messages.asStateFlow()

    init {
        wifiAwareHelper.initialize().thenApply {
            _state.update { s -> s.copy(wifiAwareInitialized = true, wiFiAwareAvailable = true) }
        }.exceptionally {
            _state.update { s -> s.copy(wiFiAwareAvailable = false) }
            Log.e(TAG, "Failed to initialize WiFi Aware", it)
        }
    }

    private val serviceDiscoveryReceiver = Consumer<ServiceDiscoveryInfo> { peer ->
        _state.update { s -> s.copy(peers = s.peers.plus(peer)) }
    }

    private val serviceLostReceiver = Consumer<PeerHandle> { peer ->
        _state.update { s -> s.copy(peers = s.peers.filterNot { it.peerHandle == peer }.toSet()) }
    }

    private val peerMessageReceiver = Consumer<WifiAwareHelper.PeerMessage> { message ->
        _messages.update { ms -> ms.plus(message) }
    }


    @SuppressLint("MissingPermission")
    fun startDiscovery(serviceType: String) {
        try {
            _state.update { oldState ->
                // Unsubscribe from any existing subscriber
                oldState.subscriber?.unsubscribe()

                // Call the Java static method
                val newSubscriber = BundleClientWifiAwareSubscriber.startDiscovery(
                    wifiAwareHelper, // Pass WifiAwareHelper instance
                    serviceType, // Service name
                    null, // serviceSpecificInfo (if needed, pass a ByteArray)
                    null, // matchFilter (if needed, pass a List<ByteArray>)
                    { message -> _messages.update { it + message } }, // Message receiver
                    { peer -> _state.update { it.copy(peers = it.peers + peer) } }, // Service discovery receiver
                    { peer -> _state.update { it.copy(peers = it.peers.filterNot { it.peerHandle == peer }.toSet()) } } // Service lost receiver
                )

                oldState.copy(subscriber = newSubscriber)
            }
        } catch (e: WifiAwareHelper.WiFiAwareException) {
            Log.e(TAG, "Failed to start discovery", e)
        }
    }

    fun stopDiscovery() {
        _state.update { oldState ->
            oldState.subscriber?.unsubscribe()
            oldState.copy(subscriber = null)
        }
    }
}
