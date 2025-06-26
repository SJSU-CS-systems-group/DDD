package net.discdd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.logging.Level
import java.util.logging.Logger

object UsbConnectionManager {
    private val logger = Logger.getLogger(UsbConnectionManager::class.java.name)

    private val _usbConnected = MutableStateFlow(false)
    val usbConnected: StateFlow<Boolean> = _usbConnected.asStateFlow()

    private var isInitialized = false
    private var usbReceiver: BroadcastReceiver? = null

    fun initialize(context: Context) {
        if (isInitialized) return

        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        _usbConnected.value = usbManager?.deviceList?.isNotEmpty() == true

        usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        logger.log(Level.INFO, "USB device attached")
                        _usbConnected.value = true
                    }

                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        logger.log(Level.INFO, "USB device detached")
                        _usbConnected.value = false
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.applicationContext.registerReceiver(usbReceiver, filter)
        isInitialized = true

        logger.log(Level.INFO, "USB Connection Manager initialized with USB connected: ${_usbConnected.value}")
    }

    fun cleanup(context: Context) {
        usbReceiver?.let {
            context.applicationContext.unregisterReceiver(it)
            usbReceiver = null
        }
        isInitialized = false
    }

    fun refreshUsbState(context: Context) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        _usbConnected.value = usbManager?.deviceList?.isNotEmpty() == true
    }

    fun getUsbReceiver(): BroadcastReceiver? {
        return usbReceiver
    }
}
