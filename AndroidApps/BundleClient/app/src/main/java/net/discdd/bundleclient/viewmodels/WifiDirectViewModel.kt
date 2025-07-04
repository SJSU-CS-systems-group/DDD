package net.discdd.bundleclient.viewmodels

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.IntentFilter
import android.content.SharedPreferences
import android.text.format.DateUtils
import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.discdd.bundleclient.R
import net.discdd.bundleclient.WifiServiceManager
import net.discdd.bundleclient.service.BundleClientService
import net.discdd.bundleclient.service.BundleClientServiceBroadcastReceiver
import net.discdd.bundleclient.service.DDDWifiDevice
import net.discdd.viewmodels.WifiBannerViewModel
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

private val logger = Logger.getLogger(WifiDirectViewModel::class.java.name)

data class PeerDevice(
    val device: DDDWifiDevice,
    val lastSeen: Long = 0,
    val lastExchange: Long = 0,
    val recencyTime: Long = 0,
    val isExchangeInProgress: Boolean = false,
)

data class WifiDirectState(
    val resultText: String = "",
    val dddWifiEnabled: Boolean = false,
    val connectedStateText: String = "",
    val discoveryActive: Boolean = false,
    val clientId: String = "Service not running",
    val peers: List<PeerDevice> = emptyList(),
    val showPeerDialog: Boolean = false,
    val dialogPeer: PeerDevice? = null, // null: hide dialog
)

class WifiDirectViewModel(
    application: Application,
): WifiBannerViewModel(application) {
    private val preferences: SharedPreferences
    private val bundleClientServiceBroadcastReceiver = BundleClientServiceBroadcastReceiver().apply {
        setViewModel(this@WifiDirectViewModel)
    }
    private val wifiService by lazy { WifiServiceManager.getService() }
    private val intentFilter = IntentFilter()

    private val _state = MutableStateFlow(WifiDirectState())
    val state = _state.asStateFlow()

    private val _backgroundExchange = MutableStateFlow(0)
    val backgroundExchange = _backgroundExchange.asStateFlow()

    init {
        preferences = getApplication<Application>().getSharedPreferences(
        BundleClientService.NET_DISCDD_BUNDLECLIENT_SETTINGS,
        Context.MODE_PRIVATE
        )
        _backgroundExchange.value = preferences.getInt(BundleClientService.NET_DISCDD_BUNDLECLIENT_SETTING_BACKGROUND_EXCHANGE, 0)
        intentFilter.addAction(BundleClientService.NET_DISCDD_BUNDLECLIENT_WIFI_ACTION)
        intentFilter.addAction(BundleClientService.NET_DISCDD_BUNDLECLIENT_LOG_ACTION)
            viewModelScope.launch {
                _backgroundExchange.collect { value ->
                    // Replace with your SharedPreferences instance
                    preferences.edit {putInt("background_exchange", value)}
                }
            }
    }

    fun initialize(serviceReadyFuture: CompletableFuture<BundleClientService>) {
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

    fun showPeerDialog(device: DDDWifiDevice) {
        val peer = wifiService?.getRecentTransport(device)
        val currentPeer = _state.value.peers.find { it.device == device }
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

    @SuppressLint("MissingPermission")
    fun exchangeMessage(device: DDDWifiDevice) {
        viewModelScope.launch {
            clearResultLogs()
            updatePeerExchangeStatus(device, true)
            wifiService?.initiateExchange(device)?.thenAccept {
                updatePeerExchangeStatus(device, false)
            }
        }
    }

    private fun updatePeerExchangeStatus(device: DDDWifiDevice, inProgress: Boolean) {
        val updatedPeers = _state.value.peers.map { peer ->
            if (peer.device.equals(device)) {
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

    fun updateState() {
        viewModelScope.launch {
            _state.update {
                it.copy(discoveryActive = wifiService?.isDiscoveryActive ?: false,
                    dddWifiEnabled = (wifiService?.dddWifi?.isDddWifiEnabled ?: false),
                    connectedStateText = wifiService?.dddWifi?.stateDescription ?: context.getString(R.string.not_connected)
                )
            }
        }
    }

    // method to update connected peers list in client
    fun updateConnectedDevices() {
        viewModelScope.launch {
            val recentTransports = wifiService?.recentTransports ?: return@launch
            val currentPeersMap = _state.value.peers.associateBy { it.device }

            val updatedPeers = recentTransports.map { recentTransport ->
                val currentPeer = currentPeersMap[recentTransport.device]
                if (currentPeer != null) {
                    // Update existing peer
                    currentPeer.copy(
                        device = recentTransport.device as DDDWifiDevice,
                        lastSeen = recentTransport.lastSeen,
                        lastExchange = recentTransport.lastExchange,
                        recencyTime = recentTransport.recencyTime
                    )
                } else {
                    // Create new peer
                    PeerDevice(
                        device = recentTransport.device as DDDWifiDevice,
                        lastSeen = recentTransport.lastSeen,
                        lastExchange = recentTransport.lastExchange,
                        recencyTime = recentTransport.recencyTime
                    )
                }
            }.toList()

            _state.update { it.copy(peers = updatedPeers) }
        }
    }


    fun discoverPeers() {
        viewModelScope.launch {
            wifiService?.discoverPeers()
        }
    }

    fun getWifiBgService(): BundleClientService? {
        return wifiService
    }

    fun clearResultLogs() {
        viewModelScope.launch {
            _state.update { it.copy(resultText = "") }
        }
    }

    fun setBackgroundExchange(value: Int) {
        // we set up a collector in the init that will save this value to SharedPreferences
        _backgroundExchange.value = value
    }
}