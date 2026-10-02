package net.discdd.bundledropbox.viewmodels

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import net.discdd.bundledropbox.DropboxStatusRepository
import net.discdd.bundledropbox.DropboxWifiSnapshot
import net.discdd.bundledropbox.DropboxWifiState
import net.discdd.bundledropbox.cycle.CycleMode
import net.discdd.viewmodels.WifiBannerViewModel

/**
 * Backs the Local Wifi tab. Mirrors the transport's `WifiDirectViewModel`: it extends android-core's
 * [WifiBannerViewModel] (deny counter for the NEARBY_WIFI_DEVICES banner) and opens system Wi-Fi
 * settings. Instead of polling `dddWifiServer.networkInfo` on service events, it collects the
 * [DropboxWifiState] StateFlow that the Java Wi-Fi code updates.
 */
class WifiDirectViewModel(application: Application) : WifiBannerViewModel(application) {
    /** Latest Wi-Fi Direct snapshot (group, clients, carriers, P2P enabled). */
    val wifi: StateFlow<DropboxWifiSnapshot> = DropboxWifiState.state

    /** Current cycle mode, so the screen can say which role the single P2P interface is in. */
    val mode: StateFlow<CycleMode> = DropboxStatusRepository.status
            .map { it.mode() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, DropboxStatusRepository.current().mode())

    /** Opens the system Wi-Fi settings (shown when Wi-Fi is off), as the transport does. */
    fun openWifiSettings() {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
    }
}
