package net.discdd.bundleclient.viewmodels

import android.app.Application
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pGroup
import android.text.format.DateUtils
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.discdd.bundleclient.BundleClientServiceBroadcastReceiver
import net.discdd.bundleclient.BundleClientWifiDirectService
import net.discdd.bundleclient.R
import net.discdd.bundleclient.WifiServiceManager
import net.discdd.viewmodels.WifiBannerViewModel
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

private val logger = Logger.getLogger(WifiDirectViewModel::class.java.name)

data class PeerDevice(
    val deviceAddress: String,
    val deviceName: String,
    val lastSeen: Long = 0,
    val lastExchange: Long = 0,
    val recencyTime: Long = 0,
    val isExchangeInProgress: Boolean = false,
)

data class WifiDirectState(
    val resultText: String = "",
    val connectedDeviceText: String = "",
    val discoveryActive: Boolean = false,
    val clientId: String = "Service not running",
    val backgroundExchange: Boolean = false,
    val peers: List<PeerDevice> = emptyList(),
    val showPeerDialog: Boolean = false,
    val dialogPeer: PeerDevice? = null, // null: hide dialog
)

class WifiDirectViewModel(
    application: Application,
): WifiBannerViewModel(application) {
    private val bundleClientServiceBroadcastReceiver = BundleClientServiceBroadcastReceiver().apply {
        setViewModel(this@WifiDirectViewModel)
    }
    private val wifiService by lazy { WifiServiceManager.getService() }
    private val peerDeviceAddresses = ArrayList<String>()
    private val intentFilter = IntentFilter()

    private val _state = MutableStateFlow(WifiDirectState())
    val state = _state.asStateFlow()

    init {
        intentFilter.addAction(BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_WIFI_EVENT_ACTION)
        intentFilter.addAction(BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_LOG_ACTION)
    }

    fun initialize(serviceReadyFuture: CompletableFuture<BundleClientWifiDirectService>) {
        viewModelScope.launch {
            serviceReadyFuture.thenAccept { service ->
                _state.update {
                    it.copy(
                        clientId = service.clientId,
                        discoveryActive = service?.isDiscoveryActive ?: false
                    )
                }
            }
        }
    }

    fun showPeerDialog(deviceAddress: String) {
        val peer = wifiService?.getPeer(deviceAddress)
        val currentPeer = _state.value.peers.find { it.deviceAddress == deviceAddress }
        currentPeer ?: return
        val updatedPeer = currentPeer.copy(
            lastSeen = peer?.lastSeen ?: 0,
            lastExchange = peer?.lastExchange ?: 0,
            recencyTime = peer?.recencyTime ?: 0,
        )
        _state.update { it.copy(dialogPeer = updatedPeer) }

    }

    fun dismissDialog() {
        _state.update { it.copy(dialogPeer = null) }
    }

    fun exchangeMessage(deviceAddress: String) {
        viewModelScope.launch {
            updatePeerExchangeStatus(deviceAddress, true)
            wifiService?.initiateExchange(deviceAddress)?.thenAccept {
                updatePeerExchangeStatus(deviceAddress, false)
            }
        }
    }

    private fun updatePeerExchangeStatus(deviceAddress: String, inProgress: Boolean) {
        val updatedPeers = _state.value.peers.map { peer ->
            if (peer.deviceAddress == deviceAddress) {
                peer.copy(isExchangeInProgress = inProgress)
            } else {
                appendResultText("${peer.deviceAddress} is not $deviceAddress")
                peer
            }
        }
        _state.update { it.copy(peers = updatedPeers) }
    }

    fun getRelativeTime(time: Long): String {
        if (time == 0L) return context.getString(R.string.never)
        return DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS).toString()
    }

    fun registerBroadcastReceiver() {
        viewModelScope.launch {
            LocalBroadcastManager.getInstance(context)
                .registerReceiver(bundleClientServiceBroadcastReceiver, intentFilter)
        }
    }

    fun unregisterBroadcastReceiver() {
        viewModelScope.launch {
            LocalBroadcastManager.getInstance(context)
                .unregisterReceiver(bundleClientServiceBroadcastReceiver)
        }
    }

    fun appendResultText(text: String?) {
        viewModelScope.launch {
            text ?: return@launch
            var resultText = _state.value.resultText
            if (resultText.count { it == '\n' } > 20) {
                val nl = resultText.indexOf('\n')
                if (nl != -1) {
                    resultText = resultText.substring(nl + 1)
                }
            }

            resultText += "$text\n"
            _state.update { it.copy(resultText = resultText) }
        }
    }

    fun updateDeliveryStatus() {
        _state.update {
            it.copy(discoveryActive = wifiService?.isDiscoveryActive ?: false)
        }
    }

    // method to update connected peers list in client
    fun updateConnectedDevices() {
        viewModelScope.launch {
            val recentTransports = wifiService?.recentTransports ?: return@launch
            val discoveredPeers = recentTransports.map { it.deviceAddress }
            val currentPeers = HashSet(peerDeviceAddresses)

            // new names (discoveredPeers - currentPeers)
            val newNames = HashSet(discoveredPeers)
            newNames.removeAll(currentPeers)

            // removed names (currentPeers - discoveredPeers)
            val removedNames = HashSet(currentPeers)
            removedNames.removeAll(discoveredPeers.toSet())
            peerDeviceAddresses.removeIf { removedNames.contains(it) }
            peerDeviceAddresses.addAll(newNames)

            val updatedPeers = peerDeviceAddresses.mapNotNull { deviceAddress ->
                val peer = wifiService?.getPeer(deviceAddress)
                if (peer != null) {
                    val currentPeer = _state.value.peers.find { it.deviceAddress == deviceAddress }
                    if (currentPeer != null) {
                        // Update existing peer
                        currentPeer.copy(
                            deviceName = peer.deviceName,
                            lastSeen = peer.lastSeen,
                            lastExchange = peer.lastExchange,
                            recencyTime = peer.recencyTime
                        )
                    } else {
                        // Create new peer
                        PeerDevice(
                            deviceAddress = deviceAddress,
                            deviceName = peer.deviceName,
                            lastSeen = peer.lastSeen,
                            lastExchange = peer.lastExchange,
                            recencyTime = peer.recencyTime
                        )
                    }
                } else {
                    null
                }
            }
            _state.update { it.copy(peers = updatedPeers) }
        }
    }

    fun updateOwnerAndGroupInfo(groupOwnerAddress: InetAddress?, groupInfo: WifiP2pGroup?) {
        viewModelScope.launch {
            val ownerNameAndAddress = context.getString(
                if (groupInfo?.owner == null) R.string.not_connected
                else R.string.connected_to_transport
            )
            _state.update { it.copy(connectedDeviceText = ownerNameAndAddress) }
        }
    }

    fun discoverPeers() {
        viewModelScope.launch {
            wifiService?.discoverPeers()
        }
    }

    fun getWifiBgService(): BundleClientWifiDirectService? {
        return wifiService
    }

    fun clearResultLogs() {
        viewModelScope.launch {
            _state.update { it.copy(resultText = "") }
        }
    }
}