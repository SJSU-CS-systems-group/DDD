package net.discdd.bundletransport.screens

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.discdd.bundletransport.utils.generateQRCode
import net.discdd.bundletransport.viewmodels.WifiDirectViewModel
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun AppShareScreen(
    viewModel: WifiDirectViewModel = viewModel(),
) {
    val url = "http://192.168.49.1:8080"
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // APK URLs
    val mailApkUrl =
        "https://github.com/SJSU-CS-systems-group/DDD-thunderbird-android/releases/latest/download/ddd-mail.apk"
    val clientApkUrl = "https://github.com/SJSU-CS-systems-group/DDD/releases/latest/download/DDDClient.apk"
    val mailApkFile = File(context.getExternalFilesDir(null), "ddd-mail.apk")
    val clientApkFile = File(context.getExternalFilesDir(null), "DDDClient.apk")
    var mailApkVersion by remember { mutableStateOf(getApkVersionInfo(context, mailApkFile)) }
    var clientApkVersion by remember { mutableStateOf(getApkVersionInfo(context, clientApkFile)) }

    // Download states
    var isDownloadingMail by remember { mutableStateOf(false) }
    var isDownloadingClient by remember { mutableStateOf(false) }
    var downloadMailProgress by remember { mutableFloatStateOf(0f) }
    var downloadClientProgress by remember { mutableFloatStateOf(0f) }
    var downloadMailStatus by remember { mutableStateOf(getApkStatus(mailApkFile, mailApkVersion)) }
    var downloadClientStatus by remember { mutableStateOf(getApkStatus(clientApkFile, clientApkVersion)) }

    // Generate QR code bitmap
    val qrBitmap = remember {
        generateQRCode(url, 300, 300)
    }
    val wifiConnectURL = viewModel.state.collectAsState().value.wifiConnectURL

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Show either download message or QR code
            if (mailApkVersion == null || clientApkVersion == null) {
                Text(
                    text = "Download APK files first to enable app sharing",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            } else {
                wifiConnectURL?.let { connectUrl ->
                    Text(
                        text = "QR code to connect your phone to this transport",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 16.dp)

                    )
                    generateQRCode(connectUrl, 200, 200)?.let { qrCodeBitmap ->
                        Image(
                            bitmap = qrCodeBitmap.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier.size(200.dp)
                        )
                        Text(
                            text = "QR code to download DDD client and mail apps",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    }

                }
                    ?: Text(
                        text = "Wifi Direct is not available.",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
            }

            // it seems a bit strange to put the button in the middle of the screen, but it separates
            // the QR codes more and makes them easier to scan
            Button(
                onClick = {
                    isDownloadingMail = true
                    isDownloadingClient = true
                    scope.launch {
                        try {
                            downloadFile(
                                context,
                                mailApkUrl,
                            ) { progress ->
                                downloadMailProgress = progress
                            }
                            mailApkVersion = getApkVersionInfo(context, mailApkFile)
                            downloadMailStatus = getApkStatus(mailApkFile, mailApkVersion)
                        } catch (e: Exception) {
                            downloadMailStatus = "Error: ${e.message}"
                        } finally {
                            isDownloadingMail = false
                        }
                    }
                    scope.launch {
                        try {
                            downloadFile(
                                context,
                                clientApkUrl,
                            ) { progress ->
                                downloadClientProgress = progress
                            }
                            clientApkVersion = getApkVersionInfo(context, clientApkFile)
                            downloadClientStatus = getApkStatus(clientApkFile, clientApkVersion)
                        } catch (e: Exception) {
                            downloadClientStatus = "Error: ${e.message}"
                        } finally {
                            isDownloadingClient = false
                        }
                    }
                }, enabled = !isDownloadingMail && !isDownloadingClient
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Download")
                    Text("Download DDD APKs")
                }
            }

            if (isDownloadingMail) {
                LinearProgressIndicator(
                    progress = { downloadMailProgress }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
            } else {
                Text(
                    text = downloadMailStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (downloadMailStatus.contains("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }

            if (isDownloadingClient) {
                LinearProgressIndicator(
                    progress = { downloadClientProgress }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
            } else {
                Text(
                    text = downloadClientStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (downloadClientStatus.contains("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        if (wifiConnectURL != null) {
            qrBitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "QR Code",
                    modifier = Modifier.size(200.dp)
                )
            }

            Text(
                text = url,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
            }
    }
}

suspend fun downloadFile(
        context: Context, url: String, onProgress: (Float) -> Unit
): File = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        val fileName = url.substringAfterLast('/')
        connection = URL(url).openConnection() as HttpURLConnection
        connection.connect()

        val fileLength = connection.contentLength
        val inputStream = connection.inputStream
        val destinationDir = context.getExternalFilesDir(null)
        val file = File(destinationDir, fileName)

        // Delete the file if it already exists
        if (file.exists()) {
            file.delete()
        }

        // Create parent directories if they don't exist
        file.parentFile?.mkdirs()

        FileOutputStream(file).use { output ->
            val buffer = ByteArray(512 * 1024)
            var downloadedSize = 0
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                downloadedSize += bytesRead

                if (fileLength > 0) {
                    val progress = downloadedSize.toFloat() / fileLength.toFloat()
                    withContext(Dispatchers.Main) {
                        onProgress(progress)
                    }
                }
            }

            output.flush()
        }

        file
    } finally {
        connection?.disconnect()
    }
}

fun getApkVersionInfo(context: Context, apkFile: File): String? {
    if (!apkFile.exists()) {
        return null
    }
    val packageManager = context.packageManager
    val packageInfo = packageManager.getPackageArchiveInfo(
            apkFile.absolutePath, PackageManager.GET_ACTIVITIES
    )

    return if (packageInfo != null) {
        packageInfo.versionName ?: "Unknown"
    } else {
        null
    }
}

fun getApkStatus(apkFile: File, versionInfo: String?): String {
    if (!apkFile.exists()) {
        return "${apkFile.name} is missing"
    }
    return if (versionInfo == null) {
        "${apkFile.name} is corrupt. Please download again."
    } else {
        "${apkFile.name} version $versionInfo"
    }
}