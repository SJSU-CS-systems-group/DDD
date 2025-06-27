package net.discdd.bundleclient.viewmodels

import android.app.Application
import android.content.Context
import android.graphics.Color
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import net.discdd.bundleclient.R
import net.discdd.bundleclient.WifiServiceManager
import net.discdd.viewmodels.UsbViewModel
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Level.INFO
import java.util.logging.Logger

class ClientUsbViewModel(
    application: Application,
) : UsbViewModel(application) {
    private val logger: Logger = Logger.getLogger(UsbViewModel::class.java.name)
    private val bundleTransmission by lazy {
        val wifiBgService = WifiServiceManager.getService()
        wifiBgService?.bundleTransmission
    }

    fun createIfDoesNotExist(parent : DocumentFile, name : String) : DocumentFile {
        var child = parent.findFile(name)
        if (child == null || !child.isDirectory) {
            child = parent.createDirectory(name)
            logger.log(INFO, "\'$name\' directory has been created")
        } else {
            logger.log(INFO, "\'$name\' directory exists")
        }
        return child ?: throw IOException ("Could not create $name")
    }
    fun transferBundleToUsb(context: Context) {
        val dddDir = usbDirectory
        if (!state.value.dddDirectoryExists || dddDir == null || bundleTransmission == null) {
            updateMessage(
                context.getString(R.string.cannot_transfer_bundle_usb_not_ready_or_directory_not_found),
                Color.RED
            )
            return
        }
        logger.log(Level.INFO, "Starting bundle creation...")
        val bundleDTO = bundleTransmission!!.generateBundleForTransmission()
        val bundleFile = bundleDTO.bundle.source

        var dddServer = createIfDoesNotExist(dddDir, "toServer")
        val targetFile = dddServer.createFile("data/octet", bundleFile.name)
        if (targetFile == null) {
            updateMessage("Error creating target file in USB directory", Color.RED)
            return
        }

        viewModelScope.launch {
            try {
                context.contentResolver.openOutputStream(targetFile.uri)?.use { outputStream ->
                    bundleFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                logger.log(Level.INFO, "Bundle creation and transfer successful")
                updateMessage("Bundle created and transferred to USB", Color.GREEN)
            } catch (e: Exception) {
                e.printStackTrace()
                updateMessage("Error creating or transferring bundle: ${e.message}", Color.RED)
            }
        }
    }
}