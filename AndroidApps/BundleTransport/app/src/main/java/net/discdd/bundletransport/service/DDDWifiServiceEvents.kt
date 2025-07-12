package net.discdd.bundletransport.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import net.discdd.bundletransport.wifi.DDDWifiServer

object DDDWifiServiceEvents {
    // some of these events (especially the state events come in bursts, so we need a backlog
    val events = MutableSharedFlow<DDDWifiServer.DDDWifiServerEvent>(5)
    private val serviceScope = CoroutineScope(Dispatchers.Default)

    @JvmStatic
    fun sendEvent(event: DDDWifiServer.DDDWifiServerEvent) {
        serviceScope.launch(Dispatchers.Default) {
            events.emit(event)
        }
    }
}