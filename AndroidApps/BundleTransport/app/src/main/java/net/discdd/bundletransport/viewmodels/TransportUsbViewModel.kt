package net.discdd.bundletransport.viewmodels

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.discdd.bundletransport.UsbFileManager
import net.discdd.pathutils.TransportPaths
import net.discdd.viewmodels.UsbViewModel
import java.io.File
import java.io.IOException
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
                    updateMessage("No files found in 'toServer' directory on USB", Color.YELLOW)
                    return@withContext
                }
                try {
                    for (bundle in bundlesToTransfer) {
                        val targetFile = destinationDir.resolve(bundle.name)
                        context.contentResolver.openOutputStream(targetFile as Uri)?.let { outputStream ->
                            context.contentResolver.openInputStream(bundle.uri).use { inputStream ->
                                inputStream?.copyTo(outputStream)
                            }
                        }
                        logger.log(INFO, "Bundle creation and transfer successful")
                    }
                    updateMessage("All bundles created and transferred to transport", Color.GREEN)
                } catch (e : Exception) {
                    e.printStackTrace()
                    updateMessage("Error creating or transferring bundle: ${e.message}", Color.RED)                }
            }
        }
    }

    fun transportTransferToUsb(context: Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Get APK from external? internal? path
                val sourceDir = DocumentFile.fromFile(transportPaths.toClientPath as File)
                val destinationDir = createIfDoesNotExist(usbDirectory!!, "toClient")
                val bundlesToTransfer = sourceDir.listFiles()
                if (bundlesToTransfer.isEmpty()) {
                    updateMessage("No files found in 'toClient' directory on Transport Device", Color.YELLOW)
                    return@withContext
                }
                try {
                    for (bundle in bundlesToTransfer) {
                        val targetFile = destinationDir.createFile("data/octet", bundle.name!!)
                        if (targetFile == null) {
                        context.contentResolver.openOutputStream(targetFile as Uri)?.let { outputStream ->
                            context.contentResolver.openInputStream(bundle.uri).use { inputStream ->
                                inputStream?.copyTo(outputStream)
                            }
                        }
                            }
                        logger.log(INFO, "Bundle creation and transfer successful")
                    }
                    updateMessage("All bundles created and transferred to transport", Color.GREEN)
                } catch (e : Exception) {
                    e.printStackTrace()
                    updateMessage("Error creating or transferring bundle: ${e.message}", Color.RED)
                }
            }
        }
    }
}