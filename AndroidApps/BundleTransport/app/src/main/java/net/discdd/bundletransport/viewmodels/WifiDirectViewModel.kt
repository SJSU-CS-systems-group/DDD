package net.discdd.bundletransport.viewmodels

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat.startActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.lifecycle.viewModelScope

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import net.discdd.bundletransport.BundleTransportWifiEvent
import net.discdd.bundletransport.R
import net.discdd.bundletransport.TransportWifiDirectService
import net.discdd.bundletransport.TransportWifiServiceManager
import net.discdd.pathutils.TransportPaths
import net.discdd.viewmodels.WifiBannerViewModel
import net.discdd.wifidirect.WifiDirectManager.WifiDirectStatus

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger
import java.util.stream.Collectors

data class WifiDirectState(
        val deviceName: String = "",
        val wifiInfo: String = "",
        val clientLog: String = "",
        val wifiStatus: String = "",
)

class WifiDirectViewModel(
        application: Application
) : WifiBannerViewModel(application) {
    private val logger = Logger.getLogger(WifiDirectViewModel::class.java.name)
    private val intentFilter = IntentFilter()
    private val bundleTransportWifiEvent = BundleTransportWifiEvent().apply {
        setViewModel(this@WifiDirectViewModel)
    }
    private val _state = MutableStateFlow(WifiDirectState())
    private val btService by lazy { TransportWifiServiceManager.getService() }
    private var transportPaths: TransportPaths = TransportPaths(context.getExternalFilesDir(null)?.toPath())
    val state = _state.asStateFlow()


    init {
        intentFilter.addAction(TransportWifiDirectService.NET_DISCDD_BUNDLETRANSPORT_WIFI_EVENT_ACTION);
        intentFilter.addAction(TransportWifiDirectService.NET_DISCDD_BUNDLETRANSPORT_CLIENT_LOG_ACTION);
        intentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
    }

    fun initialize(serviceReadyFuture: CompletableFuture<TransportWifiDirectService>) {
        viewModelScope.launch {
            serviceReadyFuture.thenAccept {
                processDeviceInfoChange()
                updateGroupInfo()
            }
        }
    }

    fun setTransportPaths(transportPaths: TransportPaths) {
        this.transportPaths = transportPaths
    }

    fun getService(): TransportWifiDirectService? {
        return btService
    }

    fun processDeviceInfoChange() {
        // NOTE: we aren't using device info here, but be aware that it can be null!
        viewModelScope.launch {
            if (btService == null) return@launch

            var serviceName = btService!!.deviceName
            _state.update {
                it.copy(deviceName = if (!serviceName.isNullOrBlank()) serviceName else "Unknown")
            }

            var status = btService!!.status
            _state.update {
                it.copy(
                        wifiStatus = when (status) {
                            WifiDirectStatus.UNDEFINED -> context.getString(R.string.check_permissions_and_that_wifi_is_enabled);
                            WifiDirectStatus.CONNECTED -> context.getString(R.string.connected);
                            WifiDirectStatus.INVITED -> context.getString(R.string.invited);
                            WifiDirectStatus.FAILED -> context.getString(R.string.failed);
                            WifiDirectStatus.AVAILABLE -> context.getString(R.string.available);
                            WifiDirectStatus.UNAVAILABLE -> context.getString(R.string.unavailable);
                        }
                )
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
                    //find candidate for nested if statement to use R.string.wifi_transport_not_active_group_info_null
                } else {
                    var addresses: String = ""
                    try {
                        val ni: NetworkInterface = NetworkInterface.getByName(gi.`interface`)
                        addresses = if (ni == null) "N/A" else ni.getInterfaceAddresses()
                                .stream()
                                .filter({ ia -> ia.getAddress() is Inet4Address })
                                .map({ ia -> ia.getAddress().getHostAddress() }).collect(Collectors.joining(", "))
                    } catch (e: SocketException) {
                        addresses = "unknown"
                    }
                    info = String.format(
                            "SSID: %s\nPassword: %s\nAddress: %s\nConnected devices: %d",
                            gi.getNetworkName(), gi.getPassphrase(), addresses, gi.getClientList().size
                    )
                }   //TO-DO connected devices seems to not along with the group info devices
                _state.update {
                    it.copy(wifiInfo = info)
                }
            }
        }
    }

    fun appendToClientLog(message: String) {
        viewModelScope.launch {
            if (state.value.clientLog.toString().lines().size > 20) {
                val nl = state.value.clientLog.lastIndexOf('\n')
                _state.update {
                    it.copy(clientLog = state.value.clientLog.substring(0, nl))
                }
            }
            _state.update {
                it.copy(clientLog = state.value.clientLog + message + '\n')
            }
        }
    }

    fun clearClientLog() {
        viewModelScope.launch {
            _state.update {
                it.copy(clientLog = "")
            }
        }
    }

    fun openInfoSettings() {
        val intent = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(context, intent, null)
    }

    fun openWifiSettings() {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(context, intent, null)
    }

    fun isWifiEnabled(): Boolean {
        val wifiManager = context.applicationContext.getSystemService(Application.WIFI_SERVICE) as WifiManager
        return wifiManager.isWifiEnabled
    }
}
