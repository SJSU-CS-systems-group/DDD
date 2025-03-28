package net.discdd.bundletransport.viewmodels

import android.app.Application
import android.content.Context.MODE_PRIVATE
import android.content.IntentFilter
import android.os.Bundle
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.discdd.bundletransport.BundleTransportActivity
import net.discdd.bundletransport.BundleTransportWifiEvent
import net.discdd.bundletransport.TransportWifiDirectService
import net.discdd.pathutils.TransportPaths
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

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
    val transportID: String = "Service not running",
    val backgroundExchange: Boolean = false,
    val peers: List<PeerDevice> = emptyList(),
    val showPeerDialog: Boolean = false,
    val dialogPeer: PeerDevice? = null, // null: hide dialog
)

class WifiDirectViewModel(
    application: Application
) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val logger = Logger.getLogger(WifiDirectViewModel::class.java.name)
    private val intentFilter = IntentFilter()
    private val bundleTransportWifiEvent = BundleTransportWifiEvent().apply {
        setViewModel(this@WifiDirectViewModel)
    }
    private val sharedPref = context.getSharedPreferences(TransportWifiDirectService.WIFI_DIRECT_PREFERENCES,
        MODE_PRIVATE)
    private val _state = MutableStateFlow(WifiDirectState())
    private var btService: TransportWifiDirectService ?= null
    private var transportPath: TransportPaths ?= null
    val state = _state.asStateFlow()


    init {
        intentFilter.addAction(TransportWifiDirectService.NET_DISCDD_BUNDLETRANSPORT_WIFI_EVENT_ACTION);
        intentFilter.addAction(TransportWifiDirectService.NET_DISCDD_BUNDLETRANSPORT_CLIENT_LOG_ACTION);
    }

    fun initialize(serviceReadyFuture: CompletableFuture<TransportWifiDirectService>) {
        viewModelScope.launch {
            serviceReadyFuture.thenAccept { service ->
                btService = service
                processDeviceInfoChange()
                updateGroupInfo()
            }
        }
        this.transportPath = transportPath
    }

    fun getActivity(): BundleTransportActivity {
        return context as BundleTransportActivity
    }

    fun processDeviceInfoChange() {

    }

    fun registerBroadcastReceiver() {
        LocalBroadcastManager.getInstance(context).registerReceiver(bundleTransportWifiEvent, intentFilter)

    }

    fun unregisterBroadcastReceiver() {

    }

    fun updateGroupInfo() {
        
    }

    fun appendToClientLog(message: String) {

    }
}
