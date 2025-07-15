package net.discdd.bundletransport.screens

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import net.discdd.bundletransport.BundleTransportService
import net.discdd.bundletransport.viewmodels.ServerUploadViewModel
import net.discdd.components.EasterEgg
import net.discdd.components.UserLogComponent
import net.discdd.utils.UserLogRepository
import net.discdd.viewmodels.ConnectivityViewModel
import net.discdd.viewmodels.SettingsViewModel

@Composable
fun ServerUploadScreen(
        uploadViewModel: ServerUploadViewModel = viewModel(),
        connectivityViewModel: ConnectivityViewModel = viewModel(),
        settingsViewModel: SettingsViewModel = viewModel(),
        preferences: SharedPreferences = LocalContext.current.getSharedPreferences(
                BundleTransportService.BUNDLETRANSPORT_PREFERENCES,
                Context.MODE_PRIVATE
        ),
        onToggle: () -> Unit,
) {
    val uploadState by uploadViewModel.state.collectAsState()
    val connectivityState by connectivityViewModel.state.collectAsState()
    val showEasterEgg by settingsViewModel.showEasterEgg.collectAsState()
    var connectServerBtn by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

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
                        .padding(16.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = {
                                focusManager.clearFocus()
                            })
                        },
                verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                    text = "TransportId: ${uploadViewModel.transportID}",
            )
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
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                OutlinedTextField(
                        value = uploadState.port,
                        onValueChange = { uploadViewModel.onPortChanged(it) },
                        label = { Text("Port Input") },
                        modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
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

            BackGroundExchange(preferences)

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

            UserLogComponent(UserLogRepository.UserLogType.EXCHANGE)

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackGroundExchange(preferences: SharedPreferences) {
    var backgroundPeriod by remember {
        mutableStateOf(
                preferences.getInt(
                        BundleTransportService.BUNDLETRANSPORT_PERIODIC_PREFERENCE,
                        0
                )
        )
    }

    var expanded by remember { mutableStateOf(false) }

    val exchangeText = if (backgroundPeriod <= 0) {
        "Disabled"
    } else {
        "${backgroundPeriod} minute(s)"
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        TextField(
                value = exchangeText,
                onValueChange = {},
                readOnly = true,
                label = { Text("Background Exchange Interval") },
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
                preferences.edit {
                    putInt(
                            BundleTransportService.BUNDLETRANSPORT_PERIODIC_PREFERENCE,
                            0
                    )
                    backgroundPeriod = 0
                }
            }) // disable background transfers
            for (i in listOf(1, 5, 10, 15, 30, 45, 60, 90, 120)) {
                DropdownMenuItem(
                        text = { Text("$i minute(s)") },
                        onClick = {
                            expanded = false
                            preferences.edit {
                                putInt(
                                        BundleTransportService.BUNDLETRANSPORT_PERIODIC_PREFERENCE,
                                        i
                                )
                            }
                            backgroundPeriod = i
                        }
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