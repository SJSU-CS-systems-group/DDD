package net.discdd.bundleclient.viewmodels

import android.app.Application
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pGroup
import android.text.format.DateUtils
import androidx.lifecycle.AndroidViewModel
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.discdd.bundleclient.BundleClientActivity
import net.discdd.bundleclient.BundleClientServiceBroadcastReceiver
import net.discdd.bundleclient.BundleClientWifiDirectService
import net.discdd.bundleclient.R
import net.discdd.bundleclient.WifiServiceManager
import java.net.InetAddress
import java.util.concurrent.CompletableFuture

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
    val deliveryStatus: String = "",
    val clientId: String = "Service not running",
    val backgroundExchange: Boolean = false,
    val peers: List<PeerDevice> = emptyList(),
    val showPeerDialog: Boolean = false,
    val dialogPeer: PeerDevice? = null, // null: hide dialog
)

class WifiDirectViewModel(
    application: Application,
): AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
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

        registerBroadcastReceiver()
    }

    fun initialize(serviceReadyFuture: CompletableFuture<BundleClientActivity>) {
        serviceReadyFuture.thenAccept {
            val service = wifiService
            if (service != null) {
                _state.update {
                    it.copy(
                        clientId = service.clientId,
                        deliveryStatus = if (service.isDiscoveryActive) "Active" else "Inactive"
                    )
                }
            }
        }
    }

    fun showPeerDialog(deviceAddress: String) {
        val peer = wifiService?.getPeer(deviceAddress)

        val currentPeer = _state.value.peers.find { it.deviceAddress == deviceAddress }
        if (currentPeer != null) {
            val updatedPeer = currentPeer.copy(
                lastSeen = peer?.lastSeen ?: 0,
                lastExchange = peer?.lastExchange ?: 0,
                recencyTime = peer?.recencyTime ?: 0,
            )

            _state.update { it.copy(dialogPeer = updatedPeer) }
        }
    }

    fun dismissDialog() {
        _state.update { it.copy(dialogPeer = null) }
    }

    fun exchangeMessage(deviceAddress: String) {
        updatePeerExchangeStatus(deviceAddress, true)

        wifiService?.initiateExchange(deviceAddress)?.thenAccept {
            updatePeerExchangeStatus(deviceAddress, false)
        }
    }

    private fun updatePeerExchangeStatus(deviceAddress: String, inProgress: Boolean) {
        val updatedPeers = _state.value.peers.map { peer ->
            if (peer.deviceAddress == deviceAddress) {
                peer.copy(isExchangeInProgress = inProgress)
            } else {
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
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(bundleClientServiceBroadcastReceiver, intentFilter)
    }

    fun unregisterBroadcastReceiver() {
        LocalBroadcastManager.getInstance(context)
            .unregisterReceiver(bundleClientServiceBroadcastReceiver)
    }

    fun appendResultText(text: String?) {
        if (text == null) return

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

    fun updateDeliveryStatus() {
        _state.update {
            it.copy(deliveryStatus = if (wifiService?.isDiscoveryActive == true) "Active" else "Inactive")
        }
    }

    // Method to update connected devices text
    fun updateConnectedDevices() {
        val recentTransports = wifiService?.recentTransports ?: return

        val discoveredPeers = recentTransports.map { it.deviceAddress }
        // figure out the new names (discoveredPeers - currentPeers)
        val currentPeers = HashSet(peerDeviceAddresses)
        // figure out the new names (discoveredPeers - currentPeers)
        val newNames = HashSet(discoveredPeers)
        newNames.removeAll(currentPeers)
        // figure out the removed names (currentPeers - discoveredPeers)
        val removedNames = HashSet(currentPeers)
        removedNames.removeAll(discoveredPeers.toSet())

        peerDeviceAddresses.removeIf { removedNames.contains(it) }
        peerDeviceAddresses.addAll(newNames)

        _state.update { it.copy(connectedDeviceText = peerDeviceAddresses.toString()) }
    }

    fun updateOwnerAndGroupInfo(groupOwnerAddress: InetAddress?, groupInfo: WifiP2pGroup?) {
        val ownerNameAndAddress = if (groupInfo == null || groupInfo.owner == null) {
            R.string.not_connected.toString()
        } else {
            R.string.connected_to_transport.toString()
        }
        _state.update { it.copy(connectedDeviceText = ownerNameAndAddress) }
    }

    fun discoverPeers() {
        wifiService?.discoverPeers()
    }

    fun getWifiBgService(): BundleClientWifiDirectService? {
        return wifiService
    }
}