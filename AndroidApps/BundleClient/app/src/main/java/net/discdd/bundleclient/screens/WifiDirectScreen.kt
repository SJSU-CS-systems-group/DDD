package net.discdd.bundleclient.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import net.discdd.bundleclient.BundleClientWifiDirectService
import net.discdd.bundleclient.R
import net.discdd.bundleclient.viewmodels.PeerDevice
import net.discdd.bundleclient.viewmodels.WifiDirectViewModel
import net.discdd.components.EasterEgg
import net.discdd.components.WifiPermissionBanner
import java.util.concurrent.CompletableFuture

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WifiDirectScreen(
    viewModel: WifiDirectViewModel = viewModel(),
    serviceReadyFuture: CompletableFuture<BundleClientWifiDirectService>,
    nearbyWifiState: PermissionState,
    preferences: SharedPreferences = LocalContext.current.getSharedPreferences(
        BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_SETTINGS,
        Context.MODE_PRIVATE
    ),
    onToggle: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val numDenied by viewModel.numDenied.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

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
                title = { Text(text = peer.deviceName) },
                text = {
                    Column {
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
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
                content = { Text(text = "ClientId: ${state.clientId}") },
                onToggle = onToggle,
            )
            Text(text = "Connected Device Addresses: ${state.connectedDeviceText}")
            Text(
                text = "Discovery Status: ${
                    if (state.discoveryActive) {
                        "Active"
                    } else "Inactive"
                }"
            )

            var checked by remember {
                mutableStateOf(
                    preferences.getBoolean(
                        BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_SETTING_BACKGROUND_EXCHANGE,
                        false
                    )
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = checked, onCheckedChange = {
                        checked = it
                        preferences.edit().putBoolean(
                            BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_SETTING_BACKGROUND_EXCHANGE, it
                        ).apply()
                    }, modifier = Modifier.weight(1f)
                )

                Text(
                    text = "Do transfers in the background", modifier = Modifier.weight(3f)
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
            Box {
                Text(text = state.resultText)
                if (state.resultText.isNotBlank()) {
                    FloatingActionButton(
                        onClick = { viewModel.clearResultLogs() },
                        modifier = Modifier.align(Alignment.BottomEnd)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(id = R.string.delete_logs)
                        )
                    }
                }
            }
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
                viewModel.exchangeMessage(peer.deviceAddress)
            }, onItemClick = {
                viewModel.showPeerDialog(peer.deviceAddress)
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
            Text(text = peer.deviceName)
        }
        Button(
            onClick = onExchangeClick,
            enabled = !peer.isExchangeInProgress,
        ) {
            Text(if (peer.isExchangeInProgress) "Exchanging..." else "Exchange")
        }
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
