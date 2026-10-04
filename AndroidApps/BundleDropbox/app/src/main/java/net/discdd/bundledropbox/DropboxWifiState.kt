package net.discdd.bundledropbox

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Immutable snapshot of the dropbox's Wi-Fi Direct situation for the Local Wifi tab.
 *
 * Generic on purpose: it covers both roles of the single P2P interface. While HOSTING the group fields
 * describe the dropbox's own `DIRECT-ddd_<baseId>` group; while VISITING the carrier fields describe
 * the BundleTransports found by DNS-SD and the one currently joined. Nothing here identifies bundle
 * contents.
 *
 * @property groupName          SSID of the group the dropbox owns, or `null` when not hosting
 * @property ownerAddress       group-owner IP (clients connect to `ownerAddress:7777`), or `null`
 * @property clientCount        number of P2P clients currently in the dropbox's group
 * @property clients            display names / MAC addresses of those clients (may be empty even when
 *                              [clientCount] > 0 if the producer only knows the count)
 * @property currentCarrier     `transportId` of the carrier being joined/exchanged with, or `null`
 * @property discoveredCarriers `transportId`s of carriers seen by DNS-SD in the current visit
 * @property p2pEnabled         `WIFI_P2P_STATE_ENABLED` as last reported by the system broadcast
 */
data class DropboxWifiSnapshot(
        val groupName: String? = null,
        val ownerAddress: String? = null,
        val clientCount: Int = 0,
        val clients: List<String> = emptyList(),
        val currentCarrier: String? = null,
        val discoveredCarriers: List<String> = emptyList(),
        val p2pEnabled: Boolean = false,
)

/**
 * Process-wide StateFlow of [DropboxWifiSnapshot], the Wi-Fi counterpart of [DropboxStatusRepository]
 * (and the dropbox's replacement for the transport's `DDDWifiServiceEvents` + polling of
 * `dddWifiServer.networkInfo`). Two writers: `wifi/DropboxWifiStateBridge` publishes whole snapshots through
 * [update], and `service/DropboxService` calls [clearRoles] on stop. The Local Wifi view model collects [state].
 */
object DropboxWifiState {
    private val _state = MutableStateFlow(DropboxWifiSnapshot())

    /** Latest snapshot; starts empty with P2P assumed disabled. */
    @JvmStatic
    val state: StateFlow<DropboxWifiSnapshot> = _state.asStateFlow()

    /** Replaces the whole snapshot. */
    @JvmStatic
    fun update(snapshot: DropboxWifiSnapshot) {
        _state.value = snapshot
    }

    /** Clears both role-specific parts (keeps [DropboxWifiSnapshot.p2pEnabled]), e.g. on service stop. */
    @JvmStatic
    fun clearRoles() {
        _state.update { DropboxWifiSnapshot(p2pEnabled = it.p2pEnabled) }
    }
}
