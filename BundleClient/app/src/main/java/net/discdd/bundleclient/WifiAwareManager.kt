package net.discdd.bundleclient

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import net.discdd.wifiaware.WifiAwareHelper
import java.util.concurrent.CompletableFuture
import java.util.logging.Level.SEVERE
import java.util.logging.Logger

object WifiAwareManager {
    private val logger = Logger.getLogger(WifiAwareManager::class.java.name)
    private var _wifiAwareBgService: BundleClientWifiAwareService? = null

    val serviceReady = CompletableFuture<BundleClientWifiAwareService>()
    private var connection: ServiceConnection? = null

    fun initializeConnection(activity: BundleClientActivity) {
        // Check if already initializing to prevent multiple initialization attempts
        if (connection != null) {
            logger.info("Connection already initialized")
            return
        }

        logger.info("Initializing service connection")
        connection = object : ServiceConnection {
            override fun onServiceConnected(className: ComponentName, service: IBinder) {
                logger.info("Service connected: $className")
                try {
                    val binder = service as BundleClientWifiAwareService.BundleClientWifiAwareServiceBinder
                    setService(binder.service)

                    // Only complete if not already completed or exceptionally completed
                    if (!serviceReady.isDone) {
                        serviceReady.complete(binder.service)
                    }
                    logger.info( "ServiceReady future completed")
                } catch (e: Exception) {
                    logger.log(SEVERE, "Error binding to service", e)
                    if (!serviceReady.isDone) {
                        serviceReady.completeExceptionally(e)
                    }
                }
            }

            override fun onServiceDisconnected(arg0: ComponentName) {
                logger.info("Service disconnected: $arg0")
                clearService()
            }
        }

        // Start the actual service binding process
        val intent = android.content.Intent(activity, BundleClientWifiAwareService::class.java)
        val success = activity.bindService(intent, connection!!, android.content.Context.BIND_AUTO_CREATE)

        if (!success) {
            logger.log(SEVERE, "Failed to bind service")
            serviceReady.completeExceptionally(Exception("Failed to bind to WifiAwareService"))
        }
    }

    fun setService(service: BundleClientWifiAwareService?) {
        logger.info("Setting WifiAwareService reference: ${service != null}")
        _wifiAwareBgService = service
    }

    fun clearService() {
        logger.info("Clearing WifiAwareService reference")
        _wifiAwareBgService = null
    }

    fun getService(): BundleClientWifiAwareService? {
        return _wifiAwareBgService
    }

    fun getConnection(): ServiceConnection {
        return connection ?: throw IllegalStateException("Connection not initialized")
    }

    fun getWifiAwareHelper(): WifiAwareHelper {
        return getService()?.wifiAwareHelper ?: throw IllegalStateException("WifiAwareService not ready")
    }
}

