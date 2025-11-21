package net.discdd.bundleclient.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat.startActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import net.discdd.bundleclient.R
import net.discdd.bundleclient.service.BundleClientService
import net.discdd.bundleclient.viewmodels.PeerDevice
import net.discdd.bundleclient.viewmodels.WifiDirectViewModel
import net.discdd.components.EasterEgg
import net.discdd.components.UserLogComponent
import net.discdd.components.WifiPermissionBanner
import net.discdd.utils.UserLogRepository
import java.util.concurrent.CompletableFuture

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WifiDirectScreen(
    viewModel: WifiDirectViewModel = viewModel(),
    serviceReadyFuture: CompletableFuture<BundleClientService>,
    nearbyWifiState: PermissionState,
    onToggle: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val numDenied by viewModel.numDenied.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val validNetwork by viewModel.validNetwork.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LaunchedEffect(Unit) {
            viewModel.initialize(serviceReadyFuture)
        }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> viewModel.registerBroadcastReceiver()
                    Lifecycle.Event.ON_PAUSE -> viewModel.unregisterBroadcastReceiver()
                    else -> {}
                }
            }

            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        state.dialogPeer?.let { peer ->
            AlertDialog(
                onDismissRequest = { viewModel.dismissDialog() },
                title = { Text(text = peer.device.description) },
                text = {
                    Column {
                        Text(text = "TransportId: ${peer.deviceId}")
                        Text(text = "Last seen: ${viewModel.getRelativeTime(peer.lastSeen)}")
                        Text(text = "Last exchange: ${viewModel.getRelativeTime(peer.lastExchange)}")
                        Text(text = "Recency: ${viewModel.getRelativeTime(peer.recencyTime)}")
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissDialog() }) {
                        Text("Close")
                    }
                }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)

        ) {
            if (!nearbyWifiState.status.isGranted) {
                WifiPermissionBanner(numDenied, nearbyWifiState) {
                    // if user denies access twice, manual access in settings is required
                    if (numDenied >= 2) {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        startActivity(context, intent, null)
                    } else {
                        viewModel.incrementNumDenied()
                        nearbyWifiState.launchPermissionRequest()
                    }
                }
            }

            /*
            * The "ClientID" section is the designated easter egg location for BundleClient
            * Click this portion 7 times in <3sec in order to toggle the easter egg!
            * */
            EasterEgg(
                content = { Text(text = "ClientId: ${state.clientId.substring(0,10)}") },
                onToggle = onToggle,
            )
            Text(text = "Wifi Direct Enabled: ${if (state.dddWifiEnabled) "✅" else "❌"}")
            Text(text = "Status: ${state.connectedStateText}")
            validNetwork?.let {
                if (!it) {
                    Text( "Please forget any WiFi networks that start with DIRECT- in your system WiFi settings")
                    Button(
                        onClick = { openWifiSettings(context) },
                    ) {
                        Text("Open WiFi Settings")
                    }
                }
            }
            Text(
                text = "Discovery Status: ${
                    if (state.discoveryActive) {
                        "Active"
                    } else "Inactive"
                }"
            )

            var expanded by remember { mutableStateOf(false) }
            val backgroundExchange by viewModel.backgroundExchange.collectAsState()

            val exchangeText = if (backgroundExchange <= 0) {
                "Disabled"
            } else {
                "${backgroundExchange} minute(s)"
            }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                TextField(
                    value = exchangeText,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Background Transfer Interval") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    DropdownMenuItem(text = { Text("disabled") }, onClick = {
                        expanded = false
                        viewModel.setBackgroundExchange(0)
                    }) // disable background transfers
                    for (i in 1..10) {
                        DropdownMenuItem(
                            text = { Text("$i minute(s)") },
                            onClick = {
                                expanded = false
                                viewModel.setBackgroundExchange(i)
                            }
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Nearby transports: ", modifier = Modifier.weight(3f)
                )

                IconButton(
                    onClick = {
                        viewModel.discoverPeers()
                    }, modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(id = R.string.refresh_peers)
                    )
                }
            }
            // peers list
            PeersList(
                viewModel = viewModel
            )
            UserLogComponent(UserLogRepository.UserLogType.WIFI)
        }
    }
}

@Composable
fun PeersList(
    viewModel: WifiDirectViewModel
) {
    val peers = viewModel.state.collectAsState().value.peers
    Column(
        modifier = Modifier.fillMaxWidth().padding(8.dp)
    ) {
        for (peer in peers) {
            PeerItem(peer = peer, onExchangeClick = {
                viewModel.exchangeMessage(peer.device)
            }, onItemClick = {
                viewModel.showPeerDialog(peer.device)
            })
        }
    }
}

@Composable
fun PeerItem(
    peer: PeerDevice,
    onExchangeClick: () -> Unit,
    onItemClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        TextButton(
            onClick = onItemClick
        ) {
            Text(text = peer.device.description.ifBlank { "TransportID: " + peer.device.id })
        }
        Button(
            onClick = onExchangeClick,
            enabled = !peer.isExchangeInProgress,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (peer.hasNewData) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(if (peer.isExchangeInProgress) "Exchanging..." else if (peer.hasNewData) "Exchange" else "Exchange (No new data)" )
        }
    }
}

fun openWifiSettings(
    context: Context
) {
    try {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
        startActivity(context, intent, null)
    } catch (e: Exception) {
        // in case some devices don't have wifi settings
        val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
        startActivity(context, intent, null)
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Preview(showBackground = true)
@Composable
fun WifiDirectScreenPreview() {
    WifiDirectScreen(
        serviceReadyFuture = CompletableFuture(),
        nearbyWifiState = rememberPermissionState(Manifest.permission.NEARBY_WIFI_DEVICES)
    ) {}
}
