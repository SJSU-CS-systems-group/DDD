package net.discdd.bundletransport.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import net.discdd.bundletransport.BundleTransportService
import net.discdd.bundletransport.R
import net.discdd.bundletransport.viewmodels.WifiDirectViewModel
import net.discdd.components.WifiPermissionBanner
import java.util.concurrent.CompletableFuture
import androidx.core.content.edit

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WifiDirectScreen(
        wifiViewModel: WifiDirectViewModel = viewModel(),
        serviceReadyFuture: CompletableFuture<BundleTransportService>,
        nearbyWifiState: PermissionState,
        preferences: SharedPreferences = LocalContext.current.getSharedPreferences(
                BundleTransportService.BUNDLETRANSPORT_PREFERENCES,
                Context.MODE_PRIVATE
        )
) {
    val state by wifiViewModel.state.collectAsState()
    val numDenied by wifiViewModel.numDenied.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var showDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
    ) {
        LaunchedEffect(Unit) {
            wifiViewModel.initialize(serviceReadyFuture)
        }

        ScrollingColumn(showActionButton = state.clientLog.isNotEmpty(), onActionClick = { wifiViewModel.clearClientLog() }) {
            if (wifiViewModel.isWifiEnabled()) {
                var backgroundPeriod by remember {
                    mutableStateOf(
                            preferences.getInt(
                                    BundleTransportService.BUNDLETRANSPORT_PERIODIC_PREFERENCE,
                                    0
                            )
                    )
                }
                val nameValid by remember {
                    derivedStateOf { state.deviceName.startsWith("ddd_") }
                }

                if (!nearbyWifiState.status.isGranted) {
                    WifiPermissionBanner(numDenied, nearbyWifiState) {
                        // if user denies access twice, manual access in settings is required
                        if (numDenied >= 2) {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            startActivity(context, intent, null)
                        } else {
                            wifiViewModel.incrementNumDenied()
                            nearbyWifiState.launchPermissionRequest()
                        }
                    }
                }

                Text(
                        text = state.wifiInfo,
                        modifier = Modifier.clickable { showDialog = true }
                )

                if (showDialog) {
                    val connectedPeers: ArrayList<String> = ArrayList()
                    wifiViewModel.getService()?.groupInfo?.let { gi ->
                        gi.clientList.forEach { c -> connectedPeers.add(c.deviceName) }
                    }

                    AlertDialog(
                            title = { Text(text = stringResource(R.string.connected_devices)) },
                            text = { Text(text = connectedPeers.toTypedArray().joinToString(", ")) },
                            onDismissRequest = { showDialog = false },
                            confirmButton = {
                                TextButton(
                                        onClick = {
                                            showDialog = false
                                        }
                                ) {
                                    Text(stringResource(R.string.dismiss))
                                }
                            }
                    )
                }

                Text(text = "Wifi Status: ${state.wifiStatus}")

                // only show the name change button if we don't have a valid device name
                // (transports must have device names starting with ddd_)
                if (nameValid) {
                    Text(text = "Device Name: ${state.deviceName}")
                } else {
                    Text(text = stringResource(
                            R.string.phone_name_must_start_with_ddd_found,
                            state.deviceName
                    ))

                    FilledTonalButton(
                            onClick = { wifiViewModel.openInfoSettings() },
                            modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.change_phone_name))
                    }
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
                                }
                            )
                        }
                    }
                }

                Text(text = stringResource(R.string.interactions_with_bundleclients))
                Text(text = state.clientLog)
            } else {
                Text(text = stringResource(
                        R.string.Wifi_disabled
                ))

                FilledTonalButton(
                        onClick = { wifiViewModel.openWifiSettings() },
                        modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Wifi Settings")
                }
            }
        }
    }
}

@Composable
fun ScrollingColumn(
        showActionButton: Boolean = false,
        onActionClick: () -> Unit = {},
        content: @Composable () -> Unit,
) {
    val scrollState = rememberScrollState()
    Box {
        Column(
                modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
        if (showActionButton) {
            FloatingActionButton(
                    onClick = { onActionClick() },
                    modifier = Modifier.align(Alignment.BottomEnd)
            ) {
                Icon(
                        imageVector = Icons.Default.Delete,
                        "Delete Logs",
                )
            }
        }
    }
}

@Preview(showBackground = true)
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun WifiDirectScreenPreview() {
    val nearbyWifiState = rememberPermissionState(Manifest.permission.NEARBY_WIFI_DEVICES)
    WifiDirectScreen(
            serviceReadyFuture = CompletableFuture(),
            nearbyWifiState = nearbyWifiState
    )
}

