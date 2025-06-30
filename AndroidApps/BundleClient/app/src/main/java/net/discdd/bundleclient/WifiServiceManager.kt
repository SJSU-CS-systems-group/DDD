package net.discdd.bundleclient

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import net.discdd.bundleclient.service.BundleClientService
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

object WifiServiceManager {
    private val logger = Logger.getLogger(WifiServiceManager::class.java.name)
    private var _wifiBgService: BundleClientService? = null

    val serviceReady = CompletableFuture<BundleClientService>()
    private lateinit var connection: ServiceConnection

    fun initializeConnection(activity: BundleClientActivity) {
         connection = object : ServiceConnection {
            override fun onServiceConnected(className: ComponentName, service: IBinder) {
                val binder = service as BundleClientService.BundleClientWifiDirectServiceBinder
                setService(binder.service)
                serviceReady.complete(binder.service)
            }
            override fun onServiceDisconnected(arg0: ComponentName) {
                clearService()
            }
        }
    }


    fun setService(service: BundleClientService?) {
        logger.info("Setting WifiService reference: ${service != null}")
        _wifiBgService = service
    }

    fun clearService() {
        logger.info("Clearing WifiService reference")
        _wifiBgService = null
    }

    fun getService(): BundleClientService? {
        return _wifiBgService
    }

    fun getConnection(): ServiceConnection {
        return connection
    }
}