package net.discdd.bundleclient.screens

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import net.discdd.bundleclient.BundleClientWifiAwareService
import net.discdd.bundleclient.WifiAwareManager
import net.discdd.bundleclient.viewmodels.WifiAwareViewModel
import java.util.concurrent.CompletableFuture

@SuppressLint("MissingPermission")
@Composable
fun WifiAwareSubscriberScreen(
    viewModel: WifiAwareViewModel = viewModel(),
    serviceReadyFuture: CompletableFuture<BundleClientWifiAwareService> = WifiAwareManager.serviceReady,
) {
    var serviceReady by remember { mutableStateOf<BundleClientWifiAwareService?>(null) }
    var serviceInitializationError by remember { mutableStateOf<String?>(null) }
    var serviceName by remember { mutableStateOf("SERVICE_NAME") }
    val wifiState = viewModel.state.collectAsState()

    LaunchedEffect(serviceReadyFuture) {
        try {
            val service = withTimeoutOrNull(20000) {
                serviceReadyFuture.await()
            }
            if (service != null) {
                serviceReady = service
            } else {
                serviceInitializationError = "Service initialization timed out"
            }
        } catch (e: Exception) {
            serviceInitializationError = "Error initializing WiFi Aware service: ${e.localizedMessage}"
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        when {
            serviceInitializationError != null -> {
                // Error state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = serviceInitializationError ?: "Unknown error",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            serviceReady == null -> {
                // Loading state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                // Main UI with input box and buttons
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Top,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TextField(
                        value = serviceName,
                        onValueChange = { serviceName = it },
                        label = { Text("Service Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            serviceReady?.let { service ->
                                viewModel.startDiscovery(serviceName)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Subscribe")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            viewModel.stopDiscovery()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Unsubscribe")
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    wifiState.value.peers.forEach {
                        Text(
                            "${it.peerHandle.hashCode()}: ${String(it.serviceSpecificInfo ?: byteArrayOf())}",
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun WifiAwareSubscriberScreenPreview() {
    WifiAwareSubscriberScreen(serviceReadyFuture = CompletableFuture())
}



