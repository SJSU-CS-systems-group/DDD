package net.discdd.bundletransport.viewmodels

import android.app.Application
import android.graphics.Color
import net.discdd.bundletransport.UsbFileManager
import net.discdd.pathutils.TransportPaths
import net.discdd.viewmodels.UsbViewModel

class TransportUsbViewModel(
        application: Application,
) : UsbViewModel(application) {
    private val transportPaths by lazy {
        TransportPaths(application.filesDir.toPath())
    }
    private val apksPath by lazy {
        application.getExternalFilesDir(null)
    }
    private var usbFileManager: UsbFileManager = UsbFileManager(storageManager, transportPaths, apksPath)

    fun populate() {
        try {
            //usbFileManager.populateUsb()
            val dddDir = usbDirectory; //"ddd_transport"
            if (usbDirectory.createDirectory("server").exists())
            updateMessage("Exchange successful!", Color.GREEN)
        } catch (e: Exception) {
            updateMessage("Exchange failed: ${e.message}", Color.RED)
        }
    }
}