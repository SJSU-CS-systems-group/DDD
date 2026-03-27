package net.discdd.bundleclient.screens

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.discdd.bundleclient.R
import net.discdd.bundleclient.viewmodels.ServerViewModel
import net.discdd.components.QRScannerScreen
import net.discdd.utils.QRCodeParser
import net.discdd.viewmodels.ConnectivityViewModel
import net.discdd.viewmodels.SettingsViewModel

@Composable
fun ServerScreen(
    serverViewModel: ServerViewModel = viewModel(),
    connectivityViewModel: ConnectivityViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val serverState by serverViewModel.state.collectAsState()
    val showEasterEgg by settingsViewModel.showEasterEgg.collectAsState()
    val connectivityState by connectivityViewModel.state.collectAsState()
    val isTransmitting by serverViewModel.isTransmitting.collectAsState()
    var showQRScanner by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val isValidPort = serverState.port.toIntOrNull()?.let { it in 1..65_535 } == true
    var enableConnectBtn by remember { mutableStateOf(false) }

    LaunchedEffect(serverState.domain, serverState.port, connectivityState.networkConnected) {
        val enable = serverState.domain.isNotBlank() &&
                isValidPort &&
                connectivityState.networkConnected
        enableConnectBtn = enable
    }

    if (showQRScanner) {
        QRScannerScreen(
            onQRCodeScanned = { scannedUrl ->
                val config = QRCodeParser.parse(scannedUrl)
                if (config != null) {
                    serverViewModel.applyScannedConfig(config)
                }
                showQRScanner = false
            },
            onDismiss = { showQRScanner = false },
        )
        return
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(16.dp)
                .pointerInput(Unit) {
                    detectTapGestures()
                },
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showEasterEgg) {
                FilledTonalButton(
                    onClick = { showQRScanner = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Scan QR Code")
                }
            }
            FilledTonalButton(
                enabled = !isTransmitting && enableConnectBtn,
                onClick = { serverViewModel.connectServer() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Connect to Bundle Server")
            }
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = serverState.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (serverState.message.isNotBlank()) {
                    FloatingActionButton(
                        onClick = { serverViewModel.clearMessage() },
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

@Preview(showBackground = true)
@Composable
fun ServerScreenPreview() {
    ServerScreen()
}