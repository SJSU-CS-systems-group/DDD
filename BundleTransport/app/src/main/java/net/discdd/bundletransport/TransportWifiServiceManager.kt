package net.discdd.bundletransport

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

object TransportWifiServiceManager {
    private val logger = Logger.getLogger(TransportWifiServiceManager::class.java.name)
    private var _btService: TransportWifiDirectService? = null

    val serviceReady = CompletableFuture<TransportWifiDirectService>()
    private lateinit var connection: ServiceConnection

    fun initialize(activity: BundleTransportActivity) {
        connection = object : ServiceConnection {
            override fun onServiceConnected(className: ComponentName, service: IBinder) {
                // We've bound to LocalService, cast the IBinder and get LocalService instance.
                val binder = service as TransportWifiDirectService.TransportWifiDirectServiceBinder
                setService(binder.service)
                serviceReady.complete(binder.service)
            }

            override fun onServiceDisconnected(arg0: ComponentName) {
                clearService()
            }
        }
    }

    fun setService(service: TransportWifiDirectService) {
        logger.info("Setting transport wifi service: ${service != null}")
        _btService = service
    }

    fun clearService() {
        logger.info("Clearing transport wifi service")
        _btService = null
    }

    fun getService(): TransportWifiDirectService? {
        return _btService
    }

    fun getConnection(): ServiceConnection {
        return connection
    }
}
