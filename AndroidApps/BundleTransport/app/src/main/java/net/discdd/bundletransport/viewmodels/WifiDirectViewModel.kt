package net.discdd.bundletransport.viewmodels

import android.app.Application
import android.content.Intent
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.core.content.ContextCompat.startActivity
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.discdd.bundletransport.BundleTransportService
import net.discdd.bundletransport.R
import net.discdd.bundletransport.TransportWifiServiceManager
import net.discdd.bundletransport.service.DDDWifiServiceEvents
import net.discdd.bundletransport.wifi.DDDWifiServer
import net.discdd.bundletransport.wifi.DDDWifiServer.WifiDirectStatus
import net.discdd.pathutils.TransportPaths
import net.discdd.viewmodels.WifiBannerViewModel
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

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
    private val _state = MutableStateFlow(WifiDirectState())
    private val btService by lazy { TransportWifiServiceManager.getService() }
    private var transportPaths: TransportPaths = TransportPaths(context.getExternalFilesDir(null)?.toPath())
    val state = _state.asStateFlow()


    init {
        viewModelScope.launch {
            updateGroupInfo()
            processDeviceInfoChange()

            DDDWifiServiceEvents.events.collect { event ->
                when (event.type) {
                    DDDWifiServer.DDDWifiServerEventType.DDDWIFISERVER_MESSAGE -> appendToClientLog(event.data)
                    DDDWifiServer.DDDWifiServerEventType.DDDWIFISERVER_DEVICENAME_CHANGED -> processDeviceInfoChange()
                    DDDWifiServer.DDDWifiServerEventType.DDDWIFISERVER_NETWORKINFO_CHANGED -> updateGroupInfo()
                }
            }
        }
    }

    fun initialize(serviceReadyFuture: CompletableFuture<BundleTransportService>) {
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

    fun getService(): BundleTransportService? {
        return btService
    }

    fun processDeviceInfoChange() {
        // NOTE: we aren't using device info here, but be aware that it can be null!
        viewModelScope.launch {
            if (btService == null) return@launch

            val serviceName = btService?.dddWifiServer?.deviceName
            _state.update {
                it.copy(deviceName = if (!serviceName.isNullOrBlank()) serviceName else "Unknown")
            }

            val status = btService?.dddWifiServer?.status ?: WifiDirectStatus.FAILED
            _state.update {
                it.copy(
                        wifiStatus = when (status) {
                            WifiDirectStatus.UNDEFINED -> context.getString(R.string.check_permissions_and_that_wifi_is_enabled)
                            WifiDirectStatus.CONNECTED -> context.getString(R.string.connected)
                            WifiDirectStatus.INVITED -> context.getString(R.string.invited)
                            WifiDirectStatus.FAILED -> context.getString(R.string.failed)
                            WifiDirectStatus.AVAILABLE -> context.getString(R.string.available)
                            WifiDirectStatus.UNAVAILABLE -> context.getString(R.string.unavailable)
                            else -> "Unavailable"
                        }
                )
            }

        }
    }

    fun updateGroupInfo() {
        viewModelScope.launch {
            btService?.dddWifiServer?.networkInfo?.let { ni ->
                viewModelScope.launch {
                    _state.update {
                        it.copy(wifiInfo = "SSID: ${ni.ssid}\nPassword: ${ni.password}\nAddress: ${ni.inetAddress}\nConnected devices: ${ni.clientList.size}")
                    }
                }
            }
        }
    }

    fun appendToClientLog(message: String) {
        viewModelScope.launch {
            if (state.value.clientLog.lines().size > 20) {
                val nl = state.value.clientLog.indexOf('\n')
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
