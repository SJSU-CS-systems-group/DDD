package net.discdd.bundleclient.screens

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import net.discdd.bundleclient.BundleClientActivity
import net.discdd.bundleclient.BundleClientWifiDirectService
import net.discdd.bundleclient.R
import net.discdd.bundleclient.viewmodels.PeerDevice
import net.discdd.bundleclient.viewmodels.WifiDirectViewModel
import net.discdd.components.ComposableTheme
import java.util.concurrent.CompletableFuture

class BundleClientWifiDirectFragment: Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ComposableTheme {
                    WifiDirectScreen(serviceReadyFuture = (activity as BundleClientActivity).serviceReady)
                }
            }
        }
    }
}

@Composable
fun WifiDirectScreen(
    viewModel: WifiDirectViewModel = viewModel(),
    serviceReadyFuture: CompletableFuture<BundleClientActivity>,
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
            }
            // peers list
            PeersList(
                peers = state.peers,
                viewModel = viewModel
            )

            Button(
                onClick = {
                    viewModel.discoverPeers()
                }
            ) {
                Text(text = stringResource(id = R.string.refresh_peers))
            }
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

@Preview(showBackground = true)
@Composable
fun WifiDirectScreenPreview() {
    WifiDirectScreen(serviceReadyFuture = CompletableFuture())
}
