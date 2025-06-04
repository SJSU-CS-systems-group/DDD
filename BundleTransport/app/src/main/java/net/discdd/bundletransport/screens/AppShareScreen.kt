package net.discdd.bundletransport.screens

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun AppShareScreen() {
    val url = "http://192.168.49.1:8080"
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // APK URLs
    val mailApkUrl =
        "https://github.com/SJSU-CS-systems-group/DDD-thunderbird-android/releases/latest/download/ddd-mail.apk"
    val clientApkUrl = "https://github.com/SJSU-CS-systems-group/DDD/releases/latest/download/DDDClient.apk"
    val mailApkFile = File(context.getExternalFilesDir(null), "ddd-mail.apk")
    val clientApkFile = File(context.getExternalFilesDir(null), "DDDClient.apk")
    var filesDownloaded by remember { mutableStateOf(mailApkFile.exists() && clientApkFile.exists()) }
    var mailApkVersion by remember { mutableStateOf(getApkVersionInfo(context, mailApkFile))}
    var clientApkVersion by remember { mutableStateOf(getApkVersionInfo(context, clientApkFile))}

    // Download states
    var isDownloadingMail by remember { mutableStateOf(false) }
    var isDownloadingClient by remember { mutableStateOf(false) }
    var downloadMailProgress by remember { mutableStateOf(0f) }
    var downloadClientProgress by remember { mutableStateOf(0f) }
    var downloadMailStatus by remember { mutableStateOf(getApkStatus(context, mailApkFile)) }
    var downloadClientStatus by remember { mutableStateOf(getApkStatus(context, clientApkFile)) }

    // Generate QR code bitmap
    val qrBitmap = remember {
        generateQRCode(url, 300, 300)
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Show either download message or QR code
            if (!filesDownloaded) {
                Text(
                    text = "Download APK files first to enable app sharing",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            } else {
                Text(
                    text = "Scan QR code to share DDD client and mail apps",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                qrBitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(), contentDescription = "QR Code", modifier = Modifier.size(250.dp)
                    )
                }

                Text(
                    text = url, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp)
                )
            }

            Button(
                onClick = {
                    isDownloadingMail = true
                    isDownloadingClient = true
                    scope.launch {
                        try {
                            val result = downloadFile(
                                context,
                                mailApkUrl,
                            ) { progress ->
                                downloadMailProgress = progress
                            }
                            val versionInfo = getApkVersionInfo(context, result)
                        } catch (e: Exception) {
                            downloadMailStatus = "Error: ${e.message}"
                        } finally {
                            isDownloadingMail = false
                        }
                    }
                    scope.launch {
                        try {
                            val result = downloadFile(
                                context,
                                clientApkUrl,
                            ) { progress ->
                                downloadClientProgress = progress
                            }
                            val versionInfo = getApkVersionInfo(context, result)
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
        }
    }
}

fun generateQRCode(content: String, width: Int, height: Int): Bitmap? {
    return try {
        val qrCodeWriter = QRCodeWriter()
        val bitMatrix = qrCodeWriter.encode(content, BarcodeFormat.QR_CODE, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(
                    x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
                )
            }
        }

        bitmap
    } catch (e: WriterException) {
        null
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
            val buffer = ByteArray(4 * 1024) // 4KB buffer
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

fun getApkVersionInfo(context: Context, apkFile: File): Pair<String, Int>? {
    val packageManager = context.packageManager
    val packageInfo = packageManager.getPackageArchiveInfo(
        apkFile.absolutePath, PackageManager.GET_ACTIVITIES
    )

    return if (packageInfo != null) {
        Pair(packageInfo.versionName ?: "Unknown", packageInfo.versionCode)
    } else {
        null
    }
}

fun getApkStatus(context: Context, apkFile: File): String {
    if (!apkFile.exists()) {
        return "${apkFile.name} is missing"
    }
    val versionInfo = getApkVersionInfo(context, apkFile)
    return if (versionInfo == null) {
        "${apkFile.name} is corrupt. Please download again."
    } else {
        "${apkFile.name} version ${versionInfo?.first ?: "Unknown"}"
    }
}