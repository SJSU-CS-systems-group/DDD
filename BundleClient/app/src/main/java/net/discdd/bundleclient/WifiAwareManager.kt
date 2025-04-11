package net.discdd.bundleclient

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import net.discdd.wifiaware.WifiAwareHelper
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

object WifiAwareManager {
    private val logger = Logger.getLogger(WifiAwareManager::class.java.name)
    private var _wifiAwareBgService: BundleClientWifiAwareService? = null

    val serviceReady = CompletableFuture<BundleClientWifiAwareService>()
    private var connection: ServiceConnection? = null

    fun initializeConnection(activity: BundleClientActivity) {
        // Check if already initializing to prevent multiple initialization attempts
        if (connection != null) {
            Log.d("WifiAwareManager", "Connection already initialized")
            return
        }

        Log.d("WifiAwareManager", "Initializing service connection")
        connection = object : ServiceConnection {
            override fun onServiceConnected(className: ComponentName, service: IBinder) {
                Log.d("WifiAwareManager", "Service connected: $className")
                try {
                    val binder = service as BundleClientWifiAwareService.BundleClientWifiAwareServiceBinder
                    setService(binder.service)

                    // Only complete if not already completed or exceptionally completed
                    if (!serviceReady.isDone) {
                        serviceReady.complete(binder.service)
                    }
                    Log.d("WifiAwareManager", "ServiceReady future completed")
                } catch (e: Exception) {
                    Log.e("WifiAwareManager", "Error binding to service", e)
                    if (!serviceReady.isDone) {
                        serviceReady.completeExceptionally(e)
                    }
                }
            }

            override fun onServiceDisconnected(arg0: ComponentName) {
                Log.d("WifiAwareManager", "Service disconnected: $arg0")
                clearService()
                // Don't reset the CompletableFuture here as it can only complete once
            }
        }

        // Start the actual service binding process
        val intent = android.content.Intent(activity, BundleClientWifiAwareService::class.java)
        val success = activity.bindService(intent, connection!!, android.content.Context.BIND_AUTO_CREATE)

        if (!success) {
            Log.e("WifiAwareManager", "Failed to bind service")
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

