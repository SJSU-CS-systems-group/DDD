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
    private var usbFileManager: UsbFileManager = UsbFileManager(storageManager, transportPaths)

    //usbTransferToTransport
    fun populate() {
        try {
            //get toServer list with DocumentFile.listFiles
            //for every file in list
                //DocumentFile.createFile(mime type, file.getName)
            usbFileManager.populateUsb()
            updateMessage("Exchange successful!", Color.GREEN)
        } catch (e: Exception) {
            updateMessage("Exchange failed: ${e.message}", Color.RED)
        }
    }

    //transportTransfer to Usb
}