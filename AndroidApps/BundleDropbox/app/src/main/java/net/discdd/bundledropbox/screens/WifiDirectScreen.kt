package net.discdd.bundledropbox.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import net.discdd.bundledropbox.DropboxWifiSnapshot
import net.discdd.bundledropbox.R
import net.discdd.bundledropbox.cycle.CycleMode
import net.discdd.bundledropbox.viewmodels.WifiDirectViewModel
import net.discdd.components.UserLogComponent
import net.discdd.components.WifiPermissionBanner
import net.discdd.utils.UserLogRepository

/**
 * Local Wifi tab, mirroring the transport's `WifiDirectScreen`: permission banner, Wi-Fi Direct
 * details, an "Open Wifi Settings" button when Wi-Fi is off, and the WIFI user log at the bottom.
 *
 * The dropbox uses its single P2P interface in two roles, so the screen shows both halves of
 * [DropboxWifiSnapshot]: the group it owns while hosting clients (name, group-owner address, connected
 * clients; tap the client line for the list, as in the transport) and the carriers it discovers/joins
 * while visiting. No QR code: clients join by Wi-Fi Direct peer address, never by SSID/password (R1 §1).
 *
 * @param permissionStates the runtime permissions P2P needs, in request order (today only
 *                         NEARBY_WIFI_DEVICES, which covers both roles). The banner asks for the first
 *                         missing one.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun WifiDirectScreen(
        wifiViewModel: WifiDirectViewModel = viewModel(),
        permissionStates: List<PermissionState>,
) {
    val wifi by wifiViewModel.wifi.collectAsState()
    val mode by wifiViewModel.mode.collectAsState()
    val numDenied by wifiViewModel.numDenied.collectAsState()
    var showClientsDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val wifiEnabled = remember { mutableStateOf(wifiManager.isWifiEnabled) }
    WifiStateObserver { enabled -> wifiEnabled.value = enabled }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Not scrollable on purpose: UserLogComponent scrolls itself and must get bounded height.
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (wifiEnabled.value) {
                val missing = permissionStates.firstOrNull { !it.status.isGranted }
                if (missing != null) {
                    WifiPermissionBanner(numDenied, missing) {
                        // Same rule as the transport: after two denials Android stops showing the
                        // dialog, so send the operator to the app's system settings instead.
                        if (numDenied >= 2) {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        } else {
                            wifiViewModel.incrementNumDenied()
                            missing.launchPermissionRequest()
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LabeledValue(stringResource(R.string.wifi_p2p_state),
                                stringResource(if (wifi.p2pEnabled) R.string.wifi_p2p_enabled else R.string.wifi_p2p_disabled))
                        LabeledValue(stringResource(R.string.wifi_role), stringResource(roleLabel(mode)))
                    }
                }

                HostCard(wifi, onClientsClick = { showClientsDialog = true })
                CarrierCard(wifi)

                if (showClientsDialog) {
                    AlertDialog(
                            title = { Text(text = stringResource(R.string.connected_devices)) },
                            text = {
                                Text(text = if (wifi.clients.isEmpty()) stringResource(R.string.wifi_none)
                                else wifi.clients.joinToString(", "))
                            },
                            onDismissRequest = { showClientsDialog = false },
                            confirmButton = {
                                TextButton(onClick = { showClientsDialog = false }) {
                                    Text(stringResource(R.string.dismiss))
                                }
                            }
                    )
                }
            } else {
                Text(text = stringResource(R.string.Wifi_disabled))
                FilledTonalButton(onClick = { wifiViewModel.openWifiSettings() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.wifi_open_settings))
                }
            }

            UserLogComponent(UserLogRepository.UserLogType.WIFI)
        }
    }
}

/** Which role the P2P interface is in right now. */
private fun roleLabel(mode: CycleMode): Int = when {
    mode.isHostSide() -> R.string.wifi_role_host
    mode.isVisitSide() -> R.string.wifi_role_visit
    mode == CycleMode.UNAVAILABLE -> R.string.wifi_role_unavailable
    else -> R.string.wifi_role_idle
}

/** The dropbox's own group (HOST role): what clients see as a "transport". */
@Composable
private fun HostCard(wifi: DropboxWifiSnapshot, onClientsClick: () -> Unit) {
    val none = stringResource(R.string.wifi_none)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.wifi_hosted_group), fontWeight = FontWeight.Bold)
            LabeledValue(stringResource(R.string.wifi_group_name), wifi.groupName ?: none)
            LabeledValue(stringResource(R.string.wifi_owner_address), wifi.ownerAddress ?: none)
            Column(Modifier.clickable(onClick = onClientsClick)) {
                LabeledValue(stringResource(R.string.wifi_connected_clients), wifi.clientCount.toString())
            }
        }
    }
}

/** Carriers (BundleTransports) found and joined during VISIT mode. */
@Composable
private fun CarrierCard(wifi: DropboxWifiSnapshot) {
    val none = stringResource(R.string.wifi_none)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.wifi_carriers), fontWeight = FontWeight.Bold)
            LabeledValue(stringResource(R.string.wifi_current_carrier), wifi.currentCarrier ?: none)
            LabeledValue(stringResource(R.string.wifi_discovered_carriers),
                    if (wifi.discoveredCarriers.isEmpty()) none else wifi.discoveredCarriers.joinToString("\n"))
        }
    }
}

/** Tracks the system Wi-Fi switch (copied from the transport's WifiDirectScreen). */
@Composable
fun WifiStateObserver(onWifiStateChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                    val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                    onWifiStateChanged(state == WifiManager.WIFI_STATE_ENABLED)
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION))
        onDispose { context.unregisterReceiver(receiver) }
    }
}
