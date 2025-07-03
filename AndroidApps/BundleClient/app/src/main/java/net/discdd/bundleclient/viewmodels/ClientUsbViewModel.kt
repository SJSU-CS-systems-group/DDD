package net.discdd.bundleclient.viewmodels

import android.app.Application
import android.content.Context
import android.graphics.Color
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.discdd.bundleclient.R
import net.discdd.bundleclient.WifiServiceManager
import net.discdd.grpc.RecencyBlob
import net.discdd.model.Bundle
import net.discdd.viewmodels.UsbViewModel
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level.INFO
import java.util.logging.Logger

class ClientUsbViewModel(
    application: Application,
) : UsbViewModel(application) {
    companion object {
        private val logger: Logger = Logger.getLogger(ClientUsbViewModel::class.java.name)
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
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val dddDir = usbDirectory
                if (!state.value.dddDirectoryExists || dddDir == null || bundleTransmission == null) {
                    updateMessage(
                        context.getString(R.string.cannot_transfer_bundle_usb_not_ready_or_directory_not_found),
                        Color.RED
                    )
                    return@withContext
                }
                logger.log(INFO, "Starting bundle creation...")
                val bundleDTO = bundleTransmission!!.generateBundleForTransmission()
                val bundleFile = bundleDTO.bundle.source

                var dddServer = createIfDoesNotExist(dddDir, "toServer")
                val targetFile = dddServer.createFile("data/octet", bundleFile.name)
                if (targetFile == null) {
                    updateMessage("Error creating target file in USB directory", Color.RED)
                    return@withContext
                }


                try {
                    context.contentResolver.openOutputStream(targetFile.uri)?.use { outputStream ->
                        bundleFile.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    logger.log(INFO, "Bundle creation and transfer successful")
                    updateMessage("Bundle created and transferred to USB", Color.GREEN)
                } catch (e: Exception) {
                    e.printStackTrace()
                    updateMessage("Error creating or transferring bundle: ${e.message}", Color.RED)
                }
            }
        }
    }
    fun usbTransferToClient(context: Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val recencyLocation = usbDirectory?.findFile("recencyBlob.bin")
                if (recencyLocation == null) {
                    updateMessage("No recency blob found on USB", Color.YELLOW)
                    return@withContext
                }
                    val recencyBlob = context.contentResolver.openInputStream(recencyLocation.uri)?.use { inputStream ->
                        val bytes = inputStream.readAllBytes()
                        RecencyBlob.parseFrom(bytes)
                    }
                    if (recencyBlob == null) {
                        updateMessage("Invalid recency blob found on USB", Color.YELLOW)
                        return@withContext
                    }
                    val toClientDir = createIfDoesNotExist(usbDirectory!!, "toClient")
                    val devicePath: Path =
                        rootDir.toPath().parent.resolve("BundleTransmission/bundle-generation/received-processing")
                    val filesToTransfer = toClientDir.listFiles()
                    if (filesToTransfer.isEmpty()) {
                        updateMessage("No files found in 'toClient' directory on USB", Color.YELLOW)
                        return@withContext
                    }
                    try {
                        val bundleIds = bundleTransmission!!.getNextBundles()
                        if (bundleIds.isNotEmpty()) {
                            val bundleId = bundleIds.first()
                            val bundleFile = toClientDir.findFile(bundleId)
                            if (bundleFile != null) {
                                val targetFile = devicePath.resolve(bundleId)
                                val result = withContext(Dispatchers.IO) {
                                    Files.newOutputStream(targetFile)?.use { outputStream ->
                                        context.contentResolver.openInputStream(bundleFile.uri)?.use { inputStream ->
                                            inputStream.copyTo(outputStream)
                                            bundleTransmission!!.processReceivedBundle(
                                                recencyBlob.senderId,
                                                Bundle(targetFile.toFile())
                                            )
                                        }
                                    }
                                }
                                logger.log(INFO, "Transferred file: ${bundleFile.name} of ${result} bytes to ${devicePath}")
                            }
                        }
                        updateMessage(
                            "Bundle transferred from 'toClient' to device received-processing successfully",
                            Color.GREEN
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                        updateMessage("Error during USB file transfer: ${e.message}", Color.RED)
                    }
                }
            }
        }
    }