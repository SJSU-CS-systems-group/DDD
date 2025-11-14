package net.discdd.bundleclient.screens

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.discdd.bundleclient.R
import net.discdd.bundleclient.viewmodels.ServerViewModel
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
    // Dialog state to confirm key reset
    var showResetDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    // Input validation
    val isValidPort = serverState.port.toIntOrNull()?.let { it in 1..65_535 } == true
    var enableConnectBtn by remember { mutableStateOf(false) }

    LaunchedEffect(serverState.domain, serverState.port, connectivityState.networkConnected) {
        val enable = serverState.domain.isNotBlank() &&
                isValidPort &&
                connectivityState.networkConnected
        enableConnectBtn = enable
    }

    // Small helper that the Save button uses
    fun onSaveServerTapped() {
        // Only prompt if values look valid; otherwise do nothing
        if (serverState.domain.isNotBlank() && isValidPort) {
            showResetDialog = true
        }
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
                OutlinedTextField(
                    value = serverState.domain,
                    onValueChange = { serverViewModel.onDomainChanged(it) },
                    label = { Text("BundleServer Domain") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                        keyboardType = KeyboardType.Uri
                    ),
                    singleLine = true
                )
                OutlinedTextField(
                    value = serverState.port,
                    onValueChange = { serverViewModel.onPortChanged(it) },
                    isError = serverState.port.isNotBlank() && !isValidPort,
                    label = { Text("Port") },
                    supportingText = {
                        if (serverState.port.isNotBlank() && !isValidPort) {
                            Text("Enter a valid port 1–65535")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                        keyboardType = KeyboardType.Number
                    ),
                    singleLine = true
                )
                FilledTonalButton(
                    enabled = serverState.domain.isNotBlank() && isValidPort,
                    onClick = { onSaveServerTapped() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Domain and Port")
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


    ResetKeysWarningDialog(
        show = showResetDialog,
        onConfirm = {
            serverViewModel.saveDomainPort()
            showResetDialog = false
        },
        onDismiss = {
            serverViewModel.revertDomainPortChanges()
            showResetDialog = false
        }
    )
}

@Composable
private fun ResetKeysWarningDialog(
    show: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!show) return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch server?") },
        text = {
            Text(
                "Switching servers will lose all previously sent Bundles"
            )
        },
        confirmButton = {
            androidx.compose.material3.FilledTonalButton(onClick = onConfirm) {
                Text("Switch & Reset Keys")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Preview(showBackground = true)
@Composable
fun ServerScreenPreview() {
    ServerScreen()
}