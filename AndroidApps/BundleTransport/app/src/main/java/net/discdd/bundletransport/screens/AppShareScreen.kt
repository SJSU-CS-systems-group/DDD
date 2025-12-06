package net.discdd.bundletransport.screens

import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.discdd.bundletransport.utils.generateQRCode
import net.discdd.bundletransport.viewmodels.AppShareViewModel
import net.discdd.bundletransport.viewmodels.WifiDirectViewModel
import net.discdd.viewmodels.ConnectivityViewModel
import java.net.Inet4Address
import java.net.NetworkInterface
import android.util.Log

@Composable
fun AppShareScreen(
        wifiViewModel: WifiDirectViewModel = viewModel(),
        appShareViewModel: AppShareViewModel = viewModel(),
        connectivityViewModel: ConnectivityViewModel = viewModel(),
) {
    val url = "http://192.168.49.1:8080"
    val wifiConnectURL = wifiViewModel.state.collectAsState().value.wifiConnectURL
    val connectivityState by connectivityViewModel.state.collectAsState()
    val mailApkVersion by appShareViewModel.mailApkVersion.collectAsState()
    val mailApkSignature by appShareViewModel.mailApkSignature.collectAsState()
    val clientApkVersion by appShareViewModel.clientApkVersion.collectAsState()
    val clientApkSignature by appShareViewModel.clientApkSignature.collectAsState()
    val appsAvailable = (mailApkVersion != null) || (clientApkVersion != null) || mailApkSignature || clientApkSignature

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Show either download message or QR code
            if (wifiConnectURL == null && !connectivityState.networkConnected) {
                Text(
                        text = "Wifi Direct and internet are not available.",
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
                    // it seems a bit strange to put the button in the middle of the screen, but it separates
                    // the QR codes more and makes them easier to scan
                    DownloadButton(appShareViewModel)
                    Text(text = " ")
                } else if (isNetworkValid()) {
                    wifiConnectURL?.also {
                        QRCodeDisplay("QR code to connect your phone to this transport", it)
                    }
                    // it seems a bit strange to put the button in the middle of the screen, but it separates
                    // the QR codes more and makes them easier to scan
                    DownloadButton(appShareViewModel)
                    QRCodeDisplay("QR code to download DDD client and mail apps", url)
                }
            }
        }
    }
}

@Composable
fun DownloadButton(viewModel: AppShareViewModel) {
    val downloadMailProgress by viewModel.downloadMailProgress.collectAsState()
    val downloadClientProgress by viewModel.downloadClientProgress.collectAsState()
    val downloadFailed by viewModel.apkDownloadFailed.collectAsState()

    Column {
        Button(
                onClick = {
                    viewModel.downloadApps()
                },
                enabled = downloadMailProgress == 1f && downloadClientProgress == 1f,
                modifier = Modifier.padding(start = 10.dp, top = 15.dp, end = 15.dp, bottom = 8.dp)
        ) {
            Row(
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Download")
                Text("Download latest DDD APKs")
            }
        }

        if (downloadMailProgress < 1f) {
            LinearProgressIndicator(
                    progress = { downloadMailProgress }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
        }

        if (downloadClientProgress < 1f) {
            LinearProgressIndicator(
                    progress = { downloadClientProgress }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
        }

        if (downloadFailed) {
            AlertDialog(
                    onDismissRequest = {
                        viewModel.resetDownloadStatus()
                    },
                    confirmButton = {
                        TextButton(
                                onClick = {
                                    viewModel.resetDownloadStatus()
                                }
                        ) {
                            Text("I understand")
                        }
                    },
                    text = {
                        Text(
                                "Downloading the APKs failed, please try downloading again."
                        )
                    },
            )
        }
    }
}

@Composable
fun QRCodeDisplay(instructions: String, contentURL: String) {
    Text(
            text = instructions,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 16.dp)
    )
    generateQRCode(contentURL, 200, 200)?.let { wifiQrCodeBitmap ->
        Image(
                bitmap = wifiQrCodeBitmap.asImageBitmap(),
                contentDescription = "QR Code",
                modifier = Modifier.size(200.dp).padding(start = 10.dp, top = 5.dp, end = 15.dp, bottom = 8.dp)
        )
    }
}

fun isNetworkValid(): Boolean {
    val ip = getWifiDirectIp()
    return ip != null
}

fun getWifiDirectIp(): String? {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        for (intf in interfaces) {
            val addresses = intf.inetAddresses
            for (addr in addresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    // Check if IP is in the typical Wi-Fi Direct subnet
                    if (addr.hostAddress.startsWith("192.168.49.")) {
                        return addr.hostAddress
                    }
                }
            }
        }
    } catch (e: Exception) {
        Log.e("WifiDirect", "Error getting Wi-Fi Direct IP", e)
    }
    return null
}