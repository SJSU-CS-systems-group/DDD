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
    var enableConnectBtn by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(serverState.domain, serverState.port, connectivityState.networkConnected) {
        val enable = serverState.domain.isNotEmpty() && serverState.port.isNotEmpty() && connectivityState.networkConnected
        enableConnectBtn = enable
    }

    val scrollState = rememberScrollState()

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
                    detectTapGestures(onTap = {
                        focusManager.clearFocus()
                    })
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
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                OutlinedTextField(
                    value = serverState.port,
                    onValueChange = { serverViewModel.onPortChanged(it) },
                    label = { Text("Port Input") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                FilledTonalButton(
                    onClick = {
                        serverViewModel.saveDomainPort()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Domain and Port")
                }
            }

            FilledTonalButton(
                enabled = !isTransmitting && enableConnectBtn,
                onClick = {
                    serverViewModel.connectServer()
                },
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
