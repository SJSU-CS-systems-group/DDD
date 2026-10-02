package net.discdd.bundledropbox

import java.util.function.UnaryOperator
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
 * `dddWifiServer.networkInfo`). The Java Wi-Fi code (`wifi/DropboxWifiHost`, `wifi/DropboxWifiJoiner`)
 * publishes through the `@JvmStatic` setters; the Local Wifi view model collects [state].
 *
 * All setters are atomic read-modify-write updates, so the host and joiner may publish from different
 * threads (broadcast receivers on the main looper, executor threads) without losing fields.
 */
object DropboxWifiState {
    private val _state = MutableStateFlow(DropboxWifiSnapshot())

    /** Latest snapshot; starts empty with P2P assumed disabled. */
    @JvmStatic
    val state: StateFlow<DropboxWifiSnapshot> = _state.asStateFlow()

    /** @return current snapshot, for Java callers. */
    @JvmStatic
    fun current(): DropboxWifiSnapshot = _state.value

    /** Replaces the whole snapshot. */
    @JvmStatic
    fun update(snapshot: DropboxWifiSnapshot) {
        _state.value = snapshot
    }

    /** Atomic update with a side-effect-free function (may run more than once under contention). */
    @JvmStatic
    fun modify(fn: UnaryOperator<DropboxWifiSnapshot>) {
        _state.update { fn.apply(it) }
    }

    /** HOST side: the dropbox's group formed or changed (pass `null`s when the group is removed). */
    @JvmStatic
    fun setHostGroup(groupName: String?, ownerAddress: String?, clients: List<String>) {
        _state.update {
            it.copy(groupName = groupName, ownerAddress = ownerAddress, clientCount = clients.size,
                    clients = clients.toList())
        }
    }

    /** HOST side: only the client count is known (e.g. from a connection-changed broadcast). */
    @JvmStatic
    fun setClientCount(count: Int) {
        _state.update { it.copy(clientCount = count) }
    }

    /** VISIT side: carrier currently joined / exchanging, or `null` after leaving its group. */
    @JvmStatic
    fun setCurrentCarrier(transportId: String?) {
        _state.update { it.copy(currentCarrier = transportId) }
    }

    /** VISIT side: carriers discovered so far in this visit (replace, not append). */
    @JvmStatic
    fun setDiscoveredCarriers(transportIds: List<String>) {
        _state.update { it.copy(discoveredCarriers = transportIds.toList()) }
    }

    /** `WIFI_P2P_STATE_CHANGED_ACTION` result. */
    @JvmStatic
    fun setP2pEnabled(enabled: Boolean) {
        _state.update { it.copy(p2pEnabled = enabled) }
    }

    /** Clears both role-specific parts (keeps [DropboxWifiSnapshot.p2pEnabled]), e.g. on service stop. */
    @JvmStatic
    fun clearRoles() {
        _state.update { DropboxWifiSnapshot(p2pEnabled = it.p2pEnabled) }
    }
}
