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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.discdd.bundletransport.utils.generateQRCode
import net.discdd.bundletransport.viewmodels.AppShareViewModel
import net.discdd.bundletransport.viewmodels.WifiDirectViewModel

@Composable
fun AppShareScreen(
        wifiViewModel: WifiDirectViewModel = viewModel(),
        appShareViewModel: AppShareViewModel = viewModel()
) {
    val url = "http://192.168.49.1:8080"
    // Generate QR code bitmap
    val downloadQrBitmap = remember {
        generateQRCode(url, 300, 300)
    }
    val wifiConnectURL = wifiViewModel.state.collectAsState().value.wifiConnectURL
    val mailApkVersion by appShareViewModel.mailApkVersion.collectAsState()
    val mailApkSignature by appShareViewModel.mailApkSignature.collectAsState()
    val clientApkVersion by appShareViewModel.clientApkVersion.collectAsState()
    val clientApkSignature by appShareViewModel.clientApkSignature.collectAsState()
    val appsAvailable = remember { mailApkVersion != null || clientApkVersion != null || mailApkSignature || clientApkSignature }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Show either download message or QR code
            if (wifiConnectURL == null) {
                Text(
                        text = "Wifi Direct is not available.",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                )

            } else {
                if (!appsAvailable) {
                    Text(
                            text = "Download APK files first to enable app sharing",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 16.dp)
                    )
                } else {
                    Text(
                            text = "QR code to connect your phone to this transport",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 16.dp)

                    )
                    generateQRCode(wifiConnectURL, 200, 200)?.let { wifiQrCodeBitmap ->
                        Image(
                                bitmap = wifiQrCodeBitmap.asImageBitmap(),
                                contentDescription = "QR Code",
                                modifier = Modifier.size(200.dp)
                        )
                    }
                }

                // it seems a bit strange to put the button in the middle of the screen, but it separates
                // the QR codes more and makes them easier to scan
                DownloadButton(appShareViewModel)

                if (appsAvailable) {
                    Text(
                            text = "QR code to download DDD client and mail apps",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 16.dp)
                    )

                    downloadQrBitmap?.let {
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
                } else {
                    // we have this little blank text to keep the button in the middle of the screen
                    Text(text = " ")
                }
            }
        }
    }
}

@Composable
fun DownloadButton(viewModel: AppShareViewModel) {
    val downloadMailProgress by viewModel.downloadMailProgress.collectAsState()
    val downloadClientProgress by viewModel.downloadClientProgress.collectAsState()
    val mailApkVersion by viewModel.mailApkVersion.collectAsState()
    val clientApkVersion by viewModel.clientApkVersion.collectAsState()

    Column {
        Button(
                onClick = {
                    viewModel.downloadApps()
                },
                enabled = downloadMailProgress == 1f && downloadClientProgress == 1f,
        ) {
            Row(
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Download")
                Text("Download DDD APKs")
            }
        }

        if (downloadMailProgress < 1f) {
            LinearProgressIndicator(
                    progress = { downloadMailProgress }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
        } else {
            Text(
                    text = mailApkVersion ?: "",
                    style = MaterialTheme.typography.bodySmall,
            )
        }

        if (downloadClientProgress < 1f) {
            LinearProgressIndicator(
                    progress = { downloadClientProgress }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
        } else {
            Text(
                    text = clientApkVersion ?: "",
                    style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}