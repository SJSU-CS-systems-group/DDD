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
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Level.INFO
import java.util.logging.Logger

class ClientUsbViewModel(
    application: Application,
) : UsbViewModel(application) {
    companion object {
        private val logger: Logger = Logger.getLogger(UsbViewModel::class.java.name)
    }
    lateinit var rootDir: File
    private val bundleTransmission by lazy {
        val wifiBgService = WifiServiceManager.getService()
        wifiBgService?.bundleTransmission
    }
    fun setRoot(dir: File) {
        rootDir = dir
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
        logger.log(INFO, "Starting bundle creation...")
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

    fun usbTransferToClient(context: Context) {
        val toClientDir = createIfDoesNotExist(usbDirectory!!, "toClient")
        val devicePath: Path =
            rootDir.toPath().getParent().resolve("BundleTransmission/bundle-generation/received-processing")
        val deviceDir = DocumentFile.fromFile(devicePath.toFile())
        val filesToTransfer = toClientDir.listFiles()
        if (filesToTransfer.isEmpty()) {
            updateMessage("No files found in 'toClient' directory on USB", Color.YELLOW)
            return
        }
        viewModelScope.launch { //coroutine for I/O-bound work
            try {
                for (file in filesToTransfer) {
                    val targetFile = deviceDir.createFile("data/octet", file.name!!)
                    if (targetFile == null) {
                        logger.log(Level.WARNING, "Failed to create file '${file.name}' in device received-processing directory")
                        continue
                    }
                    context.contentResolver.openOutputStream(targetFile.uri)?.use { outputStream ->
                        context.contentResolver.openInputStream(file.uri)?.use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    logger.log(INFO, "Transferred file: ${file.name}")
                }
                updateMessage("All files transferred from 'toClient' to device received-processing successfully", Color.GREEN)
            } catch (e: Exception) {
                e.printStackTrace()
                updateMessage("Error during USB file transfer: ${e.message}", Color.RED)
            }
        }
    }
}