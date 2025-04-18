package net.discdd.bundletransport.viewmodels

import android.app.Application
import android.graphics.Color
import net.discdd.bundletransport.UsbFileManager
import net.discdd.pathutils.TransportPaths
import net.discdd.viewmodels.UsbViewModel

class TransportUsbViewModel(
    application: Application,
): UsbViewModel(application) {
    private val transportPaths by lazy {
        TransportPaths(application.filesDir.toPath())
    }
    private var usbFileManager: UsbFileManager = UsbFileManager(storageManager, transportPaths)

    fun populate() {
        try {
            usbFileManager.populateUsb()
            updateMessage("Exchange successful!", Color.GREEN)
        } catch (e: Exception) {
            updateMessage("Exchange failed: ${e.message}", Color.RED)
        }
    }
}