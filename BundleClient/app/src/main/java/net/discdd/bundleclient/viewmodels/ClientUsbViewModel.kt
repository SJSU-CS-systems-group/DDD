package net.discdd.bundleclient.viewmodels

import android.app.Application
import android.graphics.Color
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import net.discdd.bundleclient.WifiServiceManager
import net.discdd.viewmodels.UsbViewModel
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.logging.Level

class ClientUsbViewModel(
    application: Application,
) : UsbViewModel(application) {
    private val bundleTransmission by lazy {
        val wifiBgService = WifiServiceManager.getService()
        wifiBgService?.bundleTransmission
    }

    fun transferBundleToUsb() {
        viewModelScope.launch {
            if (state.value.dddDirectoryExists && bundleTransmission != null) {
                try {
                    logger.log(Level.INFO, "Starting bundle creation...")
                    val bundleDTO = bundleTransmission!!.generateBundleForTransmission()
                    val bundleFile = bundleDTO.bundle.source
                    val targetFile = File(usbDirectory, bundleFile.name)

                    Files.copy(
                        bundleFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )

                    logger.log(Level.INFO, "Bundle creation and transfer successful")
                    updateMessage("Bundle created and transferred to USB", Color.GREEN)
                } catch (e: Exception) {
                    e.printStackTrace()
                    updateMessage("Error creating or transferring bundle: ${e.message}", Color.RED)
                }
            } else {
                updateMessage("Cannot transfer bundle: USB not ready or directory not found", Color.RED)
            }
        }
    }
}