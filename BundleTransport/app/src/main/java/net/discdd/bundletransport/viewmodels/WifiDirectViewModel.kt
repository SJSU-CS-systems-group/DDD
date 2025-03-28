package net.discdd.bundletransport.viewmodels

import android.app.Application
import android.content.Context.MODE_PRIVATE
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.discdd.bundletransport.BundleTransportActivity
import net.discdd.bundletransport.BundleTransportWifiEvent
import net.discdd.bundletransport.R
import net.discdd.bundletransport.TransportWifiDirectService
import net.discdd.pathutils.TransportPaths
import net.discdd.wifidirect.WifiDirectManager.WifiDirectStatus
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger
import java.util.stream.Collectors

data class WifiDirectState(
    val deviceNameView: String = "",
    val wifiInfoView: String = "",
    val clientLogView: String = "",
    val wifiStatusView: String = "",
    val collectDataOnClosed: Boolean = false,
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
    private val _state = MutableStateFlow(WifiDirectState())
    private var btService: TransportWifiDirectService ?= null
    private var transportPaths: TransportPaths ?= null
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
    }

    fun setTransportPaths(transportPaths: TransportPaths) {
        this.transportPaths = transportPaths
    }

    fun getActivity(): BundleTransportActivity {
        return context as BundleTransportActivity
    }

    fun processDeviceInfoChange() {
        // NOTE: we aren't using device info here, but be aware that it can be null!
        viewModelScope.launch {
            if (btService == null) return@launch
            var deviceName = btService!!.deviceName
            _state.update {
                it.copy(deviceNameView = if (deviceName != null) deviceName else "Unknown")
            }
            // only show the changeDeviceNameView if we don't have a valid device name
            // (transports must have device names starting with ddd_)
            if (deviceName != null) {
                //changeDeviceNameView?
            }
            var status = btService!!.status
            _state.update {
                it.copy(wifiStatusView = when (status) {
                    WifiDirectStatus.UNDEFINED -> context.getString(R.string.check_permissions_and_that_wifi_is_enabled);
                    WifiDirectStatus.CONNECTED -> context.getString(R.string.connected);
                    WifiDirectStatus.INVITED -> context.getString(R.string.invited);
                    WifiDirectStatus.FAILED -> context.getString(R.string.failed);
                    WifiDirectStatus.AVAILABLE -> context.getString(R.string.available);
                    WifiDirectStatus.UNAVAILABLE -> context.getString(R.string.unavailable);
                })
            }

        }
    }

    fun registerBroadcastReceiver() {
        LocalBroadcastManager.getInstance(context).registerReceiver(bundleTransportWifiEvent, intentFilter)
        updateGroupInfo()
        processDeviceInfoChange()
    }

    fun unregisterBroadcastReceiver() {
        LocalBroadcastManager.getInstance(context).unregisterReceiver(bundleTransportWifiEvent)
    }

    fun updateGroupInfo() {
        if (btService != null) {
            var gi = btService!!.groupInfo
            logger.info("Group info: " + gi)
            viewModelScope.launch {
                var info: String = ""
                if (gi == null) {
                    info = context.getString(R.string.wifi_transport_not_active)
                } else {
                    var addresses: String = ""
                    try {
                        val ni: NetworkInterface = NetworkInterface.getByName(gi.`interface`)
                        addresses = if (ni == null) "N/A" else ni.getInterfaceAddresses()
                            .stream()
                            .filter( {ia -> ia.getAddress() is Inet4Address} )
                            .map( {ia -> ia.getAddress().getHostAddress()} ).collect(Collectors.joining(", "))
                    } catch (e: SocketException) {
                        addresses= "unknown"
                    }
                    info = String.format("SSID: %s\nPassword: %s\nAddress: %s\nConnected devices: %d",
                        gi.getNetworkName(), gi.getPassphrase(), addresses, gi.getClientList().size)
                }
                _state.update {
                    it.copy(wifiInfoView = info)
                }
            }
        }
    }

    fun appendToClientLog(message: String) {
        viewModelScope.launch {
            if (state.value.clientLogView.toString().lines().size > 20) {
                val nl = state.value.clientLogView.indexOf('\n')
                _state.update {
                    it.copy(clientLogView = state.value.clientLogView.substring(0, nl))
                }
            }
            _state.update {
                it.copy(clientLogView = state.value.clientLogView + message + '\n')
            }
        }
    }
}
