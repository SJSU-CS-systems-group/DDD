package net.discdd.bundledropbox.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import net.discdd.bundledropbox.R
import net.discdd.bundledropbox.viewmodels.StatusFormat
import net.discdd.bundledropbox.viewmodels.StorageViewModel

/**
 * Storage tab: how many opaque bundles the dropbox holds in each direction, against the configured
 * capacity, plus two operator "clear" actions behind confirmation dialogs.
 *
 * Upstream = "deposited" by clients, waiting for a carrier. Downstream = "pickups" pulled from
 * carriers, waiting for clients. In v1 nothing is ever deleted automatically
 * (KeepAllRetentionPolicy). FUTURE: the server manages dropbox storage with keep/delete control ADUs
 * and learns client delivery from client-ACK reports; this screen then shows that state and the clear
 * buttons become an emergency override.
 */
@Composable
fun StorageScreen(viewModel: StorageViewModel = viewModel()) {
    val status by viewModel.status.collectAsState()
    val extras by viewModel.extras.collectAsState()
    var confirmUpstream by remember { mutableStateOf(false) }
    var confirmDownstream by remember { mutableStateOf(false) }

    LaunchedEffect(extras.message) {
        if (extras.message != null) {
            delay(3000)
            viewModel.clearMessage()
        }
    }

    // Staged (in-flight) bytes are not in DropboxStatus, so "used" here is committed bytes only.
    val used = status.upstreamBytes() + status.downstreamBytes()
    val capacity = extras.capacityBytes

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
                modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LabeledValue(stringResource(R.string.storage_upstream),
                            stringResource(R.string.count_and_bytes, status.upstreamCount(),
                                    StatusFormat.formatBytes(status.upstreamBytes())))
                    LabeledValue(stringResource(R.string.storage_downstream),
                            stringResource(R.string.count_and_bytes, status.downstreamCount(),
                                    StatusFormat.formatBytes(status.downstreamBytes())))
                    LabeledValue(stringResource(R.string.storage_used_of_capacity),
                            stringResource(R.string.storage_used_value, StatusFormat.formatBytes(used),
                                    StatusFormat.formatBytes(capacity)))
                    LinearProgressIndicator(
                            progress = { if (capacity > 0) (used.toFloat() / capacity).coerceIn(0f, 1f) else 0f },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                    LabeledValue(stringResource(R.string.storage_device_free),
                            StatusFormat.formatBytes(extras.deviceFreeBytes))
                    Text(stringResource(R.string.storage_capacity_hint), style = MaterialTheme.typography.bodySmall)
                }
            }

            OutlinedButton(onClick = { confirmUpstream = true }, modifier = Modifier.fillMaxWidth(),
                    enabled = status.upstreamCount() > 0) {
                Text(stringResource(R.string.storage_clear_upstream))
            }
            OutlinedButton(onClick = { confirmDownstream = true }, modifier = Modifier.fillMaxWidth(),
                    enabled = status.downstreamCount() > 0) {
                Text(stringResource(R.string.storage_clear_downstream))
            }

            extras.message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }

            // FUTURE: server keep/delete control ADUs (RetentionPolicy + RelayStore.delete) and client-ACK
            // reports (DeliveryLog) will manage this storage; show their state here instead of this note.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.storage_future_title), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.storage_future_text), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (confirmUpstream) {
        ConfirmClearDialog(
                title = stringResource(R.string.storage_clear_upstream),
                text = stringResource(R.string.storage_confirm_upstream, status.upstreamCount()),
                onConfirm = { confirmUpstream = false; viewModel.clearUpstream() },
                onDismiss = { confirmUpstream = false },
        )
    }
    if (confirmDownstream) {
        ConfirmClearDialog(
                title = stringResource(R.string.storage_clear_downstream),
                text = stringResource(R.string.storage_confirm_downstream, status.downstreamCount()),
                onConfirm = { confirmDownstream = false; viewModel.clearDownstream() },
                onDismiss = { confirmDownstream = false },
        )
    }
}

@Composable
private fun ConfirmClearDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
            title = { Text(title) },
            text = { Text(text) },
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.storage_confirm_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
