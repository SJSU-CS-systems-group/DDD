package net.discdd.bundledropbox.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import net.discdd.bundledropbox.DropboxPrefs
import net.discdd.bundledropbox.R
import net.discdd.bundledropbox.viewmodels.DropboxSettingsViewModel
import net.discdd.bundledropbox.viewmodels.ServerKeysForm
import net.discdd.components.QRScannerScreen

/**
 * Settings tab: the role-cycle timers and relay options from 03 Architecture "Settings", persisted to
 * [DropboxPrefs]. The running service applies changes through its preference listener
 * (`RoleCycleController.updateConfig`). Timer changes apply from the next dwell (a running dwell's
 * deadline never moves), which the tab says next to the timers and in the "Saved" text (audit i7).
 *
 * The "Server keys (QR)" section imports the trusted DDD server's keys from BundleClient's server URL, scanned
 * with android-core's [QRScannerScreen] or pasted (a dropbox may have no usable camera). See
 * 06 Storage Management Design, "Server keys for the endpoint".
 */
@Composable
fun SettingsScreen(viewModel: DropboxSettingsViewModel = viewModel()) {
    val form by viewModel.form.collectAsState()
    val keysForm by viewModel.serverKeys.collectAsState()
    val status by viewModel.status.collectAsState()

    if (keysForm.scanning) {
        QRScannerScreen(onQRCodeScanned = viewModel::onScanned, onDismiss = viewModel::stopScan)
        return
    }

    LaunchedEffect(form.saved) {
        if (form.saved) {
            delay(2000)
            viewModel.clearSaved()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
                modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.settings_timers), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.settings_timers_note), style = MaterialTheme.typography.bodySmall)
            NumberField(R.string.settings_client_period, R.string.settings_client_period_help, form.clientPeriodS,
                    form.errors[DropboxPrefs.KEY_CLIENT_PERIOD_S], viewModel::onClientPeriodChange)
            NumberField(R.string.settings_transport_period, R.string.settings_transport_period_help,
                    form.transportPeriodS, form.errors[DropboxPrefs.KEY_TRANSPORT_PERIOD_S],
                    viewModel::onTransportPeriodChange)
            NumberField(R.string.settings_drain_cap, R.string.settings_drain_cap_help, form.drainCapS,
                    form.errors[DropboxPrefs.KEY_DRAIN_CAP_S], viewModel::onDrainCapChange)
            NumberField(R.string.settings_max_storage, R.string.settings_max_storage_help, form.maxStorageMb,
                    form.errors[DropboxPrefs.KEY_MAX_STORAGE_MB], viewModel::onMaxStorageChange)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = viewModel::save, enabled = form.dirty) { Text(stringResource(R.string.settings_save)) }
                OutlinedButton(onClick = viewModel::revert, enabled = form.dirty) {
                    Text(stringResource(R.string.settings_revert))
                }
                if (form.saved) Text(stringResource(R.string.settings_saved), color = MaterialTheme.colorScheme.primary)
            }

            HorizontalDivider()
            Text(stringResource(R.string.settings_behavior), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
            SwitchRow(R.string.settings_return_after_visit, R.string.settings_return_after_visit_help,
                    form.returnAfterVisit, viewModel::onReturnAfterVisitChange)
            SwitchRow(R.string.settings_rotate_id, R.string.settings_rotate_id_help,
                    form.rotateAdvertisedId, viewModel::onRotateAdvertisedIdChange)
            SwitchRow(R.string.settings_keep_awake, R.string.settings_keep_awake_help,
                    form.keepAwake, viewModel::onKeepAwakeChange)
            SwitchRow(R.string.settings_start_on_boot, R.string.settings_start_on_boot_help,
                    form.startOnBoot, viewModel::onStartOnBootChange)

            HorizontalDivider()
            TextButton(onClick = viewModel::resetToDefaults) { Text(stringResource(R.string.settings_reset_defaults)) }

            HorizontalDivider()
            ServerKeysSection(
                    trusted = status.control().serverKeys(),
                    form = keysForm,
                    onUrlChange = viewModel::onServerUrlChange,
                    onScan = viewModel::startScan,
                    onImport = viewModel::importServerKeys,
                    onBuiltIn = viewModel::useBuiltInServerKeys,
            )
        }
    }
}

@Composable
private fun NumberField(label: Int, help: Int, value: String, error: Int?, onChange: (String) -> Unit) {
    OutlinedTextField(
            value = value,
            onValueChange = { v -> onChange(v.filter { it.isDigit() }) },
            label = { Text(stringResource(label)) },
            supportingText = { Text(stringResource(error ?: help)) },
            isError = error != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(label: Int, help: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(help), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Trusted server keys: current source, scan or paste a server URL, or go back to the built-in keys. */
@Composable
private fun ServerKeysSection(
        trusted: String,
        form: ServerKeysForm,
        onUrlChange: (String) -> Unit,
        onScan: () -> Unit,
        onImport: () -> Unit,
        onBuiltIn: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_server_keys), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.settings_server_keys_help), style = MaterialTheme.typography.bodySmall)
        LabeledValue(stringResource(R.string.settings_server_keys_current),
                trusted.ifEmpty { stringResource(R.string.value_unknown) })
        OutlinedButton(onClick = onScan, enabled = !form.busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_server_keys_scan))
        }
        OutlinedTextField(
                value = form.url,
                onValueChange = onUrlChange,
                label = { Text(stringResource(R.string.settings_server_keys_url)) },
                supportingText = { Text(stringResource(R.string.settings_server_keys_url_help)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onImport, enabled = !form.busy && form.url.isNotBlank()) {
                Text(stringResource(R.string.settings_server_keys_import))
            }
            OutlinedButton(onClick = onBuiltIn, enabled = !form.busy) {
                Text(stringResource(R.string.settings_server_keys_builtin))
            }
        }
        form.message?.let {
            Text(it, color = if (form.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium)
        }
    }
}
