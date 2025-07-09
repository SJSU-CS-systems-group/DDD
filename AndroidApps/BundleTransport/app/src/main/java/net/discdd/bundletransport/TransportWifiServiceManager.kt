package net.discdd.bundletransport

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

object TransportWifiServiceManager {
    private val logger = Logger.getLogger(TransportWifiServiceManager::class.java.name)
    private var _btService: BundleTransportService? = null

    val serviceReady = CompletableFuture<BundleTransportService>()
    val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance.
            val binder = service as BundleTransportService.TransportWifiDirectServiceBinder
            setService(binder.service)
            serviceReady.complete(binder.service)
        }

        override fun onServiceDisconnected(ignore: ComponentName) {
            clearService()
        }
    }

    fun setService(service: BundleTransportService) {
        logger.info("Setting transport wifi service: ${service}")
        _btService = service
    }

    fun clearService() {
        logger.info("Clearing transport wifi service")
        _btService = null
    }

    fun getService(): BundleTransportService? {
        return _btService
    }

}
