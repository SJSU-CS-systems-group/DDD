package net.discdd.bundleclient

import android.app.Activity.MODE_PRIVATE
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

object WifiServiceManager {
    private val logger = Logger.getLogger(WifiServiceManager::class.java.name)
    private var _wifiBgService: BundleClientWifiDirectService? = null
    val serviceReady = CompletableFuture<MainActivity>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BundleClientWifiDirectService.BundleClientWifiDirectServiceBinder
            setService(binder.service)
            serviceReady.complete(MainActivity())
        }
        override fun onServiceDisconnected(arg0: ComponentName) {
            clearService()
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