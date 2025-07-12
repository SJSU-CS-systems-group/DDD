package net.discdd.bundletransport.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import net.discdd.bundletransport.wifi.DDDWifiServer

object DDDWifiServiceEvents {
    val events = MutableSharedFlow<DDDWifiServer.DDDWifiServerEvent>()
    private val serviceScope = CoroutineScope(Dispatchers.Default)

    @JvmStatic
    fun sendEvent(event: DDDWifiServer.DDDWifiServerEvent) {
        serviceScope.launch(Dispatchers.Default) {
            events.emit(event)
        }
    }
}