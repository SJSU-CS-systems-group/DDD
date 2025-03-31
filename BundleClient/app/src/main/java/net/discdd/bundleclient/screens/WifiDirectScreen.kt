package net.discdd.bundleclient.screens

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import java.util.concurrent.CompletableFuture

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun WifiDirectScreen(
    viewModel: WifiDirectViewModel = viewModel(),
    serviceReadyFuture: CompletableFuture<BundleClientWifiDirectService>,
    nearbyWifiState: PermissionState,
    preferences: SharedPreferences = LocalContext.current.getSharedPreferences(
        BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_SETTINGS,
        Context.MODE_PRIVATE
    )
) {
    val state by viewModel.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // TODO: remove isVisible
            val showBanner by remember { mutableStateOf(!nearbyWifiState.status.isGranted) }
            if (!nearbyWifiState.status.isGranted) {
                WifiPermissionBanner(
                    isVisible = showBanner,
                    onEnableClick = {
                        nearbyWifiState.launchPermissionRequest()
                    }
                )
            }

            Text(text = "ClientId: ${state.clientId}")
            Text(text = "Connected Device Addresses: ${state.connectedDeviceText}")
            Text(text = "Discovery Status: ${state.deliveryStatus}")
            var checked by remember {
                mutableStateOf(
                    preferences.getBoolean(
                        BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_SETTING_BACKGROUND_EXCHANGE,
                        false
                    )
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = {
                        checked = it
                        preferences.edit().putBoolean(
                            BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_SETTING_BACKGROUND_EXCHANGE,
                            it
                        ).apply()
                    }
                )

                Text(text = "Do transfers in the background")

                IconButton(
                    onClick = {
                        viewModel.discoverPeers()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(id = R.string.refresh_peers)
                    )
                }
            }
            // peers list
            PeersList(
                peers = state.peers,
                viewModel = viewModel
            )
        }
    }
}

@Composable
fun PeersList(
    peers: List<PeerDevice>,
    viewModel: WifiDirectViewModel
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        items(peers) { peer ->
            PeerItem(
                peer = peer,
                onExchangeClick = {
                    viewModel.exchangeMessage(peer.deviceAddress)
                },
                onItemClick = {
                    viewModel.showPeerDialog(peer.deviceAddress)
                }
            )
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
        nearbyWifiState = rememberPermissionState(
            Manifest.permission.NEARBY_WIFI_DEVICES
        )
    )
}
