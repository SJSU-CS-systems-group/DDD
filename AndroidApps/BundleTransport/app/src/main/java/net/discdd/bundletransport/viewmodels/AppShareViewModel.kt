package net.discdd.bundletransport.viewmodels

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.discdd.bundletransport.R
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.logging.Level
import java.util.logging.Logger

// APK URLs
const val mailApkUrl =
        "https://github.com/SJSU-CS-systems-group/DDD-thunderbird-android/releases/latest/download/ddd-mail.apk"
const val clientApkUrl = "https://github.com/SJSU-CS-systems-group/DDD/releases/latest/download/DDDClient.apk"

class AppShareViewModel(
        val myApplication: Application
) : AndroidViewModel(myApplication) {
    private val logger = Logger.getLogger(AppShareViewModel::class.java.name)
    private val mailApkFile by lazy {
        File(myApplication.getExternalFilesDir(null), "ddd-mail.apk").toPath()
    }
    private val clientApkFile by lazy {
        File(myApplication.getExternalFilesDir(null), "DDDclient.apk").toPath()
    }
    private var _downloadMailProgress = MutableStateFlow(1f)
    val downloadMailProgress = _downloadMailProgress.asStateFlow()
    private var _downloadClientProgress = MutableStateFlow(1f)
    val downloadClientProgress = _downloadClientProgress.asStateFlow()
    private var _mailApkVersion = MutableStateFlow<String?>(null)
    val mailApkVersion = _mailApkVersion.asStateFlow()
    private var _clientApkVersion = MutableStateFlow<String?>(null)
    val clientApkVersion = _clientApkVersion.asStateFlow()
    private var _clientApkSignature = MutableStateFlow<Boolean>(false)
    val clientApkSignature = _clientApkSignature.asStateFlow()
    private var _mailApkSignature = MutableStateFlow<Boolean>(false)
    val mailApkSignature = _mailApkSignature.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _mailApkVersion.value = getApkVersionInfo(mailApkFile)
            _mailApkSignature.value = checkApkSignature(mailApkFile)
            _clientApkVersion.value = getApkVersionInfo(clientApkFile)
            _clientApkSignature.value = checkApkSignature(clientApkFile)
        }
    }


    private fun downloadFile(
            url: String, dest: Path, progress: MutableStateFlow<Float>
    ): String? {
        val fileName = url.substringAfterLast('/')
        try {
            var connection = URL(url).openConnection() as HttpURLConnection
            connection.connect()

            val fileLength = connection.contentLength
            val inputStream = connection.inputStream

            val tmpPath = dest.resolveSibling("${fileName}.tmp")
            // Delete the file if it already exists
            if (Files.exists(tmpPath)) {
                Files.delete(tmpPath)
            }

            // Create parent directories if they don't exist
            Files.createDirectories(tmpPath.parent)

            Files.newOutputStream(tmpPath).use { output ->
                val buffer = ByteArray(512 * 1024)
                var downloadedSize = 0
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedSize += bytesRead

                    if (fileLength > 0) {
                        progress.value = downloadedSize.toFloat() / fileLength.toFloat()
                    }
                }

                output.flush()
            }
            connection.disconnect()
            Files.move(tmpPath, dest, StandardCopyOption.REPLACE_EXISTING)
            return getApkVersionInfo(dest)
        } catch (e: IOException) {
            // Clean up the temporary file if download fails
            if (Files.exists(dest)) {
                Files.delete(dest)
            }
            return myApplication.getString(R.string.download_failed, dest.fileName, e.message)
        }
    }

    private fun getApkVersionInfo(apkPath: Path): String? {
        if (!Files.exists(apkPath)) {
            return null
        }
        val packageManager = myApplication.packageManager
        val packageInfo = packageManager.getPackageArchiveInfo(
                apkPath.toAbsolutePath().toString(), PackageManager.GET_ACTIVITIES
        )

        return "${apkPath.fileName} ${packageInfo?.versionName ?: "Error"}"
    }

    private fun checkApkSignature(apkPath: Path): Boolean {
        if (!Files.exists(apkPath)) {
            return false
        }
        val packageManager = myApplication.packageManager
        val packageInfo = packageManager.getPackageArchiveInfo(
                apkPath.toAbsolutePath().toString(),
                PackageManager.GET_SIGNING_CERTIFICATES
        )

        if (packageInfo == null) return false
        val signingInfo = packageInfo.signingInfo
        logger.log(Level.FINE, "signingInfo: ${signingInfo}, apk info: ${apkPath.toAbsolutePath()}")
        return signingInfo != null
    }

    fun downloadApps() {
        viewModelScope.launch(Dispatchers.IO) {
            _downloadMailProgress.value = 0f
            _mailApkVersion.value = downloadFile(mailApkUrl, mailApkFile, _downloadMailProgress)
            _downloadMailProgress.value = 1f
        }
        viewModelScope.launch(Dispatchers.IO) {
            _downloadClientProgress.value = 0f
            _clientApkVersion.value = downloadFile(clientApkUrl, clientApkFile, _downloadClientProgress)
            _downloadClientProgress.value = 1f
        }
    }
}
