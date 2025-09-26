package net.discdd.bundletransport.screens

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat.startActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import net.discdd.bundletransport.BundleTransportService
import net.discdd.bundletransport.R
import net.discdd.bundletransport.utils.generateQRCode
import net.discdd.bundletransport.viewmodels.WifiDirectViewModel
import net.discdd.components.UserLogComponent
import net.discdd.components.WifiPermissionBanner
import net.discdd.utils.UserLogRepository
import java.util.concurrent.CompletableFuture

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WifiDirectScreen(
        wifiViewModel: WifiDirectViewModel = viewModel(),
        serviceReadyFuture: CompletableFuture<BundleTransportService>,
        nearbyWifiState: PermissionState,
) {
    val state by wifiViewModel.state.collectAsState()
    val numDenied by wifiViewModel.numDenied.collectAsState()
    var showConnectedPeersDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val wifiState = remember { mutableStateOf(wifiManager.isWifiEnabled) }
    WifiStateObserver { enabled ->
        wifiState.value = enabled
    }

    Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
    ) {
        LaunchedEffect(Unit) {
            wifiViewModel.initialize(serviceReadyFuture)
        }

        Column {
            if (wifiState.value) {
                if (!nearbyWifiState.status.isGranted) {
                    WifiPermissionBanner(numDenied, nearbyWifiState) {
                        // if user denies access twice, manual access in settings is required
                        if (numDenied >= 2) {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            startActivity(context, intent, null)
                        } else {
                            wifiViewModel.incrementNumDenied()
                            nearbyWifiState.launchPermissionRequest()
                        }
                    }
                }
                Row {
                    Column {
                        Text(
                                text = state.wifiInfo,
                                modifier = Modifier.clickable { showConnectedPeersDialog = true }
                        )

                        if (showConnectedPeersDialog) {
                            val connectedPeers = wifiViewModel.getService()?.dddWifiServer
                                    ?.networkInfo?.clientList ?: emptyList<String>()

                            AlertDialog(
                                    title = { Text(text = stringResource(R.string.connected_devices)) },
                                    text = { Text(text = connectedPeers.toTypedArray().joinToString(", ")) },
                                    onDismissRequest = { showConnectedPeersDialog = false },
                                    confirmButton = {
                                        TextButton(
                                                onClick = {
                                                    showConnectedPeersDialog = false
                                                }
                                        ) {
                                            Text(stringResource(R.string.dismiss))
                                        }
                                    }
                            )
                        }

                        Text(text = "Wifi Status: ${state.wifiStatus}")
                    }
                    state.wifiConnectURL?.let { url ->
                        generateQRCode(url, 500, 500)?.let {
                            Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "QR Code",
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        }
                    }
                }
            } else {
                Text(text = stringResource(
                        R.string.Wifi_disabled
                ))

                FilledTonalButton(
                        onClick = { wifiViewModel.openWifiSettings() },
                        modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Wifi Settings")
                }
            }

            UserLogComponent(UserLogRepository.UserLogType.WIFI)
        }
    }
}

@Composable
fun WifiStateObserver(
        onWifiStateChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val wifiReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                    val state = intent.getIntExtra(
                            WifiManager.EXTRA_WIFI_STATE,
                            WifiManager.WIFI_STATE_UNKNOWN
                    )
                    onWifiStateChanged(state == WifiManager.WIFI_STATE_ENABLED)
                }
            }
        }

        val filter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)
        context.registerReceiver(wifiReceiver, filter)

        // Cleanup when the Composable leaves the composition
        onDispose {
            context.unregisterReceiver(wifiReceiver)
        }
    }
}


@Preview(showBackground = true)
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun WifiDirectScreenPreview() {
    val nearbyWifiState = rememberPermissionState(Manifest.permission.NEARBY_WIFI_DEVICES)
    WifiDirectScreen(
            serviceReadyFuture = CompletableFuture(),
            nearbyWifiState = nearbyWifiState
    )
}

