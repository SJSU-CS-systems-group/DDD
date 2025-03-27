package net.discdd.bundletransport.viewmodels

import android.app.Application


import androidx.lifecycle.AndroidViewModel

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
    application: Application
) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()


    init {

    }

    fun exchange
}