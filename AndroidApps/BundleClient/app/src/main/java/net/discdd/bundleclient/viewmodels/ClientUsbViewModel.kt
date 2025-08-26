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
                    appendMessage(
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
                    appendMessage("Error creating target file in USB directory", Color.RED)
                    return@withContext
                }


                try {
                    context.contentResolver.openOutputStream(targetFile.uri)?.use { outputStream ->
                        bundleFile.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    logger.log(INFO, "Bundle creation and transfer successful")
                    appendMessage("Bundle created and transferred to USB", Color.GREEN)
                } catch (e: Exception) {
                    e.printStackTrace()
                    appendMessage("Error creating or transferring bundle: ${e.message}", Color.RED)
                }
            }
        }
    }
    fun usbTransferToClient(context: Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val toClientDir = createIfDoesNotExist(usbDirectory!!, "toClient")
                val recencyLocation = toClientDir.findFile("recencyBlob.bin")
                if (recencyLocation == null) {
                    appendMessage("No recency blob found on USB", Color.YELLOW)
                    return@withContext
                }
                    val recencyBlob = context.contentResolver.openInputStream(recencyLocation.uri)?.use { inputStream ->
                        val bytes = inputStream.readAllBytes()
                        RecencyBlob.parseFrom(bytes)
                    }
                    if (recencyBlob == null) {
                        appendMessage("Invalid recency blob found on USB", Color.YELLOW)
                        return@withContext
                    }
//                    val devicePath: Path = rootDir.toPath().parent.resolve("BundleTransmission/bundle-generation/received-processing")
                val devicePath: Path =
                    rootDir.toPath().parent.resolve("Shared/received-bundles")
                    val filesToTransfer = toClientDir.listFiles()
                    if (filesToTransfer.isEmpty()) {
                        appendMessage("No files found in 'toClient' directory on USB", Color.YELLOW)
                        return@withContext
                    }
                    try {
                        val bundleIds = bundleTransmission!!.getNextBundles()
                        logger.log(INFO, "Bundle IDs expected: ${bundleIds}")
                        if (bundleIds.isNotEmpty()) {
                            for (bundleId in bundleIds) {
                                val bundleFile = toClientDir.findFile(bundleId)
                                logger.log(INFO, "Target bundle ID: ${bundleId}")
                                //logger.log(INFO, "toClientDir files: ${toClientDir.listFiles()}")
                                if (bundleFile != null) {
                                    val targetFile = devicePath.resolve(bundleId)
                                    val result = withContext(Dispatchers.IO) {
                                        Files.newOutputStream(targetFile)?.use { outputStream ->
                                            context.contentResolver.openInputStream(bundleFile.uri)?.use { inputStream ->
                                                inputStream.copyTo(outputStream)
                                                val receivedBundleLocation: Path =
                                                    rootDir.toPath().parent.resolve("BundleTransmission/bundle-generation/to-send")
                                                        .resolve(bundleId)
//                                                bundleTransmission!!.processReceivedBundle(
//                                                    recencyBlob.senderId,
//                                                    Bundle(receivedBundleLocation.toFile()))
                                                bundleTransmission!!.processReceivedBundle(
                                                    recencyBlob.senderId,
                                                    Bundle(targetFile.toFile()))
                                            }
                                        }
                                    }
                                    logger.log(INFO, "Transferred file: ${bundleFile.name} of ${result} bytes to ${devicePath}")
                                }
                            }
                        }
                        appendMessage(
                            "Bundle transferred from 'toClient' to device received-processing successfully",
                            Color.GREEN
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                        appendMessage("Error during USB file transfer: ${e.message}", Color.RED)
                    }
                }
            }
        }
    }