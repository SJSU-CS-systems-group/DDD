package net.discdd.bundleclient.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.discdd.bundleclient.WifiServiceManager
import net.discdd.client.bundletransmission.BundleTransmission
import java.util.stream.Collectors

data class BundleManagerApp (
    val name: String,
    val lastOut: Long,
    val ackedOut: Long,
    val lastIn: Long,
    val ackedIn: Long,
)

data class BundleManagerState(
    val appData: List<BundleManagerApp> = emptyList(),
)

class BundleManagerViewModel(
    application: Application,
): AndroidViewModel(application) {
    var bundleTransmission: BundleTransmission? = null
    private val _state = MutableStateFlow(BundleManagerState())
    val state = _state.asStateFlow()

    init {
        bundleTransmission = WifiServiceManager.getService()?.bundleTransmission
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val sendADUs = bundleTransmission?.applicationDataManager?.sendADUsStorage
            val recvADUs = bundleTransmission?.applicationDataManager?.receiveADUsStorage

            if (sendADUs == null || recvADUs == null) {
                return@launch
            }
            val list = sendADUs.getAllClientApps(true).map {
                val sendMeta = sendADUs.getMetadata(null, it.appId)
                val recvMeta = recvADUs.getMetadata(null, it.appId)

                BundleManagerApp(
                    name = it.appId.substringAfterLast('.'),
                    lastOut = sendMeta.lastAduAdded,
                    ackedOut = sendMeta.lastAduDeleted,
                    lastIn = recvMeta.lastAduAdded,
                    ackedIn = recvMeta.lastAduAdded,
                )
            }.collect(Collectors.toList())
            _state.update {
                it.copy(
                    appData = list,
                )
            }
        }
    }
}

