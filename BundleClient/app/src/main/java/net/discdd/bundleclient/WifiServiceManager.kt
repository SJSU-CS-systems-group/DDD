package net.discdd.bundleclient

import java.util.logging.Logger

object WifiServiceManager {
    private val logger = Logger.getLogger(WifiServiceManager::class.java.name)
    private var _wifiBgService: BundleClientWifiDirectService? = null

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
}