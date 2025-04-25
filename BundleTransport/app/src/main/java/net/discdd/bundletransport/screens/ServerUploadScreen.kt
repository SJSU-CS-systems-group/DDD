package net.discdd.bundletransport.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import net.discdd.bundletransport.viewmodels.ServerUploadViewModel
import net.discdd.components.EasterEgg
import net.discdd.viewmodels.ConnectivityViewModel
import net.discdd.viewmodels.SettingsViewModel

@Composable
fun ServerUploadScreen(
        uploadViewModel: ServerUploadViewModel = viewModel(),
        connectivityViewModel: ConnectivityViewModel = viewModel(),
        settingsViewModel: SettingsViewModel = viewModel(),
        onToggle: () -> Unit,
) {
    val uploadState by uploadViewModel.state.collectAsState()
    val connectivityState by connectivityViewModel.state.collectAsState()
    val showEasterEgg by settingsViewModel.showEasterEgg.collectAsState()
    var connectServerBtn by remember { mutableStateOf(false) }

    LaunchedEffect(uploadState.domain, uploadState.port, connectivityState.networkConnected) {
        val enable =
                uploadState.domain.isNotEmpty() && uploadState.port.isNotEmpty() && connectivityState.networkConnected
        connectServerBtn = enable
    }
    LaunchedEffect(uploadState.message) {
        if (uploadState.message != null) {
            delay(5000)
            uploadViewModel.clearMessage()
        }
    }

    Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
    ) {
        Column(
                modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                    onClick = { uploadViewModel.connectServer() },
                    enabled = connectServerBtn,
                    modifier = Modifier.fillMaxWidth()
            ) {
                Text("Connect to Bundle Server")
            }

            if (showEasterEgg) {
                OutlinedTextField(
                        value = uploadState.domain,
                        onValueChange = { uploadViewModel.onDomainChanged(it) },
                        label = { Text("Domain Input") },
                        modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                )
                OutlinedTextField(
                        value = uploadState.port,
                        onValueChange = { uploadViewModel.onPortChanged(it) },
                        label = { Text("Port Input") },
                        modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                )
                FilledTonalButton(
                        onClick = { uploadViewModel.saveDomainPort() },
                        modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Domain and Port")
                }
                FilledTonalButton(
                        onClick = { uploadViewModel.restoreDomainPort() },
                        modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Restore Domain and Port")
                }
            }

            Row(
                    modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
            ) {
                /*
                * The "toClient" section is the designated easter egg location for BundleTransport
                * Click this portion 7 times in <3sec in order to toggle the easter egg!
                * */
                EasterEgg(
                        content = { Text(text = "toClient: ", fontSize = 20.sp) },
                        onToggle = onToggle,
                )
                Text(
                        text = uploadState.clientCount,
                        fontSize = 20.sp
                )
                Text(
                        text = "    toServer: ",
                        fontSize = 20.sp
                )
                Text(
                        text = uploadState.serverCount,
                        fontSize = 20.sp
                )
                Spacer(modifier = Modifier.weight(1f))
                FilledTonalButton(
                        onClick = { uploadViewModel.reloadCount() },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reload Counts",
                            modifier = Modifier.size(24.dp)
                    )
                }
            }

            uploadState.message?.let { message ->
                Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ServerUploadPreview() {
    ServerUploadScreen() {}
}