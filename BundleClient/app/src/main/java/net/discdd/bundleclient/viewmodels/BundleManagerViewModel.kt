package net.discdd.bundleclient.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.discdd.bundleclient.R
import net.discdd.bundleclient.WifiServiceManager

import net.discdd.client.bundletransmission.BundleTransmission;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.Path;

data class BundleManagerState(
    val numberBundlesSent: String = "0",
    val numberBundlesReceived: String = "0",
)

class BundleManagerViewModel(
    application: Application,
): AndroidViewModel(application) {
    var bundleTransmission: BundleTransmission? = null
    private val _state = MutableStateFlow(BundleManagerState())
    val state = _state.asStateFlow()

    init {
        bundleTransmission = WifiServiceManager.getService()?.getBundleTransmission()
        refresh()
    }

    fun getADUcount(ADUPath: Path?): String {
        ADUPath ?: "0"
        try {
            val stream = Files.newDirectoryStream(ADUPath)
            for (path in stream) {
                if (path.fileName.toString().equals("net.discdd.k9")) {
                    // check for "metadata.json" and exclude it
                    if (path.toFile().isDirectory()) {
                        // Get the list of files and exclude "metadata.json"
                        val filteredFiles =
                            path.toFile().listFiles {dir, name -> name != "metadata.json" }
                                ?.map { it.name }?.toTypedArray() ?: emptyArray()
                        if (filteredFiles.isNullOrEmpty())
                            return "0"
                        return filteredFiles.size.toString()
                    }
                    return path.toFile().list()?.size?.toString() ?: "0"
                }
            }
        }
        catch (e : Exception) {
            when (e) {
                is IOException, is DirectoryIteratorException -> {
                    System.err.println("Error reading the directory: ${e.message}");
                    return "0";
                }
            }
        }
        return "0"
    }

    fun refresh() {
        viewModelScope.launch {
            var path: Path? = bundleTransmission?.getClientPaths()?.sendADUsPath
            try {
                _state.update {
                    it.copy(numberBundlesSent = getADUcount(path),
                        numberBundlesReceived = getADUcount(path))
                }
            } catch (e : Exception) {
                System.err.println("Error updating ADU count: ${e.message}");
                _state.update {
                    it.copy(numberBundlesSent = "Error",
                        numberBundlesReceived = "Error")
                }
            }
        }
    }
}

