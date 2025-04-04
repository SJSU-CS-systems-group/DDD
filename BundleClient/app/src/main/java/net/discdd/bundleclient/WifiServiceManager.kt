package net.discdd.bundleclient

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

object WifiServiceManager {
    private val logger = Logger.getLogger(WifiServiceManager::class.java.name)
    private var _wifiBgService: BundleClientWifiDirectService? = null

    val serviceReady = CompletableFuture<BundleClientWifiDirectService>()
    private lateinit var connection: ServiceConnection

    fun initializeConnection(activity: BundleClientActivity) {
         connection = object : ServiceConnection {
            override fun onServiceConnected(className: ComponentName, service: IBinder) {
                val binder = service as BundleClientWifiDirectService.BundleClientWifiDirectServiceBinder
                setService(binder.service)
                serviceReady.complete(binder.service)
            }
            override fun onServiceDisconnected(arg0: ComponentName) {
                clearService()
            }
        }
    }


    fun setService(service: BundleClientWifiDirectService?) {
        logger.info("Setting WifiService reference: ${service != null}")
        _wifiBgService = service
    }

    fun clearService() {
        logger.info("Clearing WifiService reference")
        _wifiBgService = null
    }

    fun getService(): BundleClientWifiDirectService? {
        return _wifiBgService
    }

    fun getConnection(): ServiceConnection {
        return connection
    }
}