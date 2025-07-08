package net.discdd.bundletransport.viewmodels

import android.app.Application
import android.content.Context
import android.graphics.Color
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.discdd.pathutils.TransportPaths
import net.discdd.viewmodels.UsbViewModel
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level.INFO
import java.util.logging.Logger

class TransportUsbViewModel(
        application: Application,
) : UsbViewModel(application) {
    companion object {
        private val logger: Logger = Logger.getLogger(TransportUsbViewModel::class.java.name)
    }
    private val transportPaths by lazy {
        TransportPaths(application.filesDir.toPath())
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

    fun usbTransferToTransport(context: Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val sourceDir = createIfDoesNotExist(usbDirectory!!, "toServer")
                val destinationDir = transportPaths.toServerPath
                val bundlesToTransfer = sourceDir.listFiles()
                if (bundlesToTransfer.isEmpty()) {
                    appendMessage("No files found in 'toServer' directory on USB", Color.YELLOW)
                    return@withContext
                }
                try {
                    for (bundle in bundlesToTransfer) {
                        val targetFile = destinationDir.resolve(bundle.name)
                        Files.newOutputStream(targetFile)?.let { outputStream ->
                            context.contentResolver.openInputStream(bundle.uri).use { inputStream ->
                                inputStream?.copyTo(outputStream)
                            }
                        }
                        try {
                            bundle.delete()
                            appendMessage("Deleted bundle on usb: ${bundle.name}", Color.GREEN)
                        } catch (e : Exception) {
                            e.printStackTrace()
                            appendMessage("Error deleting bundle on usb: ${e.message}", Color.RED)
                        }
                        logger.log(INFO, "Bundle from usb transferred to transport successfully")
                    }
                    appendMessage("Bundle from usb transferred to transport successfully", Color.GREEN)
                } catch (e : Exception) {
                    e.printStackTrace()
                    appendMessage("Error transferring from usb to transport: ${e.message}", Color.RED)
                }
            }
        }
    }

    fun transportTransferToUsb(context: Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // TODO: Get APK from external? internal? path
                val sourceDir = transportPaths.toClientPath.toFile()
                val destinationDir = createIfDoesNotExist(usbDirectory!!, "toClient")
                val bundlesToTransfer : List<File> = sourceDir.walk().toList()
                if (bundlesToTransfer.isEmpty()) {
                    logger.log(INFO, "No files found in 'toClient' directory on Transport Device")
                    appendMessage("No files found in 'toClient' directory on Transport Device", Color.YELLOW)
                    return@withContext
                }
                try {
                    for (bundle in bundlesToTransfer) {
                        val targetFile = destinationDir.createFile("data/octet", bundle.name)
                        if (targetFile == null) {
                            Files.newOutputStream(targetFile)?.let { outputStream ->
                                Files.newInputStream(bundle as Path?).use { inputStream ->
                                inputStream?.copyTo(outputStream)
                            }
                        }
                            }
                        logger.log(INFO, "Bundle from transport transferred to usb successfully")
                    }
                    appendMessage("Bundle from transport transferred to usb successfully", Color.GREEN)
                } catch (e : Exception) {
                    e.printStackTrace()
                    logger.log(INFO, "Error transferring from transport to usb: ${e.message}")
                    appendMessage("Error transferring from transport to usb: ${e.message}", Color.RED)
                }
            }
        }
    }
}