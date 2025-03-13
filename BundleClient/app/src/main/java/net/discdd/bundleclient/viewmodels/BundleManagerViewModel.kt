package net.discdd.bundleclient.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.discdd.bundleclient.R
import net.discdd.bundleclient.WifiServiceManager

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.Fragment;
import net.discdd.client.bundletransmission.BundleTransmission;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths

data class BundleManagerState(
    val numberBundlesSent: String = "",
    val numberBundlesReceived: String = "",
    val bundleTransmission: BundleTransmission? = null,
)

class BundleManagerViewModel(
    application: Application,
): AndroidViewModel(application) {
    private val _state = MutableStateFlow(BundleManagerState())
    val state = _state.asStateFlow()

    fun onCreate() {
        _state.update { it.copy(
            numberBundlesSent = R.id.numberBundlesSent.toString(),
            numberBundlesReceived = R.id.numberBundlesReceived.toString(),
            bundleTransmission = WifiServiceManager.getService()?.getBundleTransmission(),
        ) }
    }

    fun getADUcount(ADUPath : Path): String {
        if (ADUPath == null) {
            return "0"
        }

        try {
            val stream = Files.newDirectoryStream(ADUPath)
            for (path in stream) {
                if (path.fileName.toString().equals("com.fsck.k9.debug")) {
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
                    System.err.println("Error reading the directory: + ${e.message}");
                    return "0";
                }
            }
        }
        return "0"
    }

    fun refresh() {
        _state.update {
            bundleViewModel: BundleManagerViewModel = viewModel(),
            val managerState by bundleViewModel.state.collectAsState()
            it.copy(numberBundlesSent = getADUcount(bundleTransmission.getClientPaths().sendADUsPath))
        }
    }
}

