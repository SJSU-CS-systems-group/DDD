package net.discdd.bundleclient.screens

import android.app.Activity
import android.net.wifi.aware.ServiceDiscoveryInfo
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.ViewGroup
import android.os.Bundle
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.discdd.bundleclient.BundleClientActivity
import net.discdd.bundleclient.viewmodels.WifiAwareViewModel
import net.discdd.theme.ComposableTheme
import net.discdd.wifiaware.WifiAwareHelper
import java.nio.charset.Charset
import java.util.concurrent.CompletableFuture
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider


class BundleClientWifiAwareFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ComposableTheme {
                    WifiAwareSubscriberScreen(
                        serviceReadyFuture = (activity as BundleClientActivity).serviceReady
                    )
                }
            }
        }
    }


@Composable
fun WifiAwareSubscriberScreen(
    darkTheme: Boolean = isSystemInDarkTheme(),
    serviceReadyFuture: CompletableFuture<BundleClientActivity>

) {
    val context = LocalContext.current
    val wifiAwareHelper = remember { WifiAwareHelper(context) }
    val viewModel: WifiAwareViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return WifiAwareViewModel(wifiAwareHelper) as T
            }
        }
    )

    val state by viewModel.state.collectAsState()
    val messages by viewModel.messages.collectAsState()

    // Service type for WiFi Aware
    var serviceType by remember { mutableStateOf("com.example.bundleclient") }

    // Dialog state for message details
    var showMessageDetails by remember { mutableStateOf(false) }
    var selectedMessage by remember { mutableStateOf<WifiAwareHelper.PeerMessage?>(null) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (!state.wifiAwareAvailable) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "WiFi Aware is not available on this device",
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        (context as? Activity)?.onBackPressed()
                    }) {
                        Text("Use WiFi Direct Instead")
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "WiFi Aware Subscriber",
                    style = MaterialTheme.typography.headlineMedium
                )

                // Status information
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Status: ${if (state.wifiAwareInitialized) "Initialized" else "Initializing..."}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Subscriber: ${if (state.subscriber != null) "Active" else "Inactive"}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }

                // Service type input
                OutlinedTextField(
                    value = serviceType,
                    onValueChange = { serviceType = it },
                    label = { Text("Service Type") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Discovery buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.startDiscovery(serviceType) },
                        modifier = Modifier.weight(1f),
                        enabled = state.wifiAwareInitialized
                    ) {
                        Text(text = "Discover")
                    }

                    Button(
                        onClick = { viewModel.stopDiscovery() },
                        modifier = Modifier.weight(1f),
                        enabled = state.subscriber != null
                    ) {
                        Text(text = "Stop Discovery")
                    }
                }

                // Discovered peers
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Discovered Peers (${state.peers.size})",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        if (state.peers.isEmpty()) {
                            Text(
                                text = "No peers discovered yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                            ) {
                                items(state.peers.toList()) { peer ->
                                    PeerItem(peer = peer)
                                }
                            }
                        }
                    }
                }

                // Messages
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Messages (${messages.size})",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        if (messages.isEmpty()) {
                            Text(
                                text = "No messages received yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(messages) { message ->
                                    MessageItem(
                                        message = message,
                                        onClick = {
                                            selectedMessage = message
                                            showMessageDetails = true
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Message details dialog
        if (showMessageDetails && selectedMessage != null) {
            AlertDialog(
                onDismissRequest = { showMessageDetails = false },
                title = { Text("Message Details") },
                text = {
                    val message = selectedMessage?.message?.toString(Charset.defaultCharset()) ?: ""
                    Column {
                        Text("Peer Handle: ${selectedMessage?.peerHandle?.toString() ?: ""}")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Message Content:")
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showMessageDetails = false }) {
                        Text("Close")
                    }
                }
            )
        }
    }
}


@Composable
fun PeerItem(peer: ServiceDiscoveryInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp)
    ) {
        val peerInfo = peer.serviceSpecificInfo?.toString(Charset.defaultCharset()) ?: "No specific info"
        Text(
            text = "Info: $peerInfo",
            style = MaterialTheme.typography.bodySmall
        )

        Text(
            text = "Handle: ${peer.peerHandle}",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun MessageItem(
    message: WifiAwareHelper.PeerMessage,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            // Display first 30 chars of message or whatever fits
            val messagePreview = message.message.toString(Charset.defaultCharset()).let {
                if (it.length > 30) "${it.substring(0, 30)}..." else it
            }

            Text(
                text = messagePreview,
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "From: ${message.peerHandle}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun WifiAwareSubscriberScreenPreview() {
        WifiAwareSubscriberScreen(
            serviceReadyFuture = CompletableFuture()
        )
    }
}



