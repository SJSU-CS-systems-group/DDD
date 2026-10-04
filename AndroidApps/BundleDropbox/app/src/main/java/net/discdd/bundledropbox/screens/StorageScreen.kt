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
import net.discdd.bundledropbox.status.DropboxStatus
import net.discdd.bundledropbox.viewmodels.StatusFormat
import net.discdd.bundledropbox.viewmodels.StorageViewModel

/**
 * Storage tab: how many opaque bundles the dropbox holds in each direction, against the configured
 * capacity, the state of server-managed cleanup, plus two operator "clear" actions and a "reset recency
 * blob" recovery action, each behind a confirmation dialog.
 *
 * Upstream = "deposited" by clients, waiting for a carrier. Downstream = "pickups" pulled from
 * carriers, waiting for clients. Bundles are deleted automatically only when a server retention plan names
 * them (`control/PlanExecutor`, 06 Storage Management Design); every bundle held is therefore "awaiting a
 * server decision". The clear buttons are a manual override that does not wait for the server.
 */
@Composable
fun StorageScreen(viewModel: StorageViewModel = viewModel()) {
    val status by viewModel.status.collectAsState()
    val extras by viewModel.extras.collectAsState()
    var confirmUpstream by remember { mutableStateOf(false) }
    var confirmDownstream by remember { mutableStateOf(false) }
    var confirmRecencyReset by remember { mutableStateOf(false) }

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
            // Audit M6: manual recovery from a recency blob that clients reject (e.g. from another server).
            OutlinedButton(onClick = { confirmRecencyReset = true }, modifier = Modifier.fillMaxWidth(),
                    enabled = status.recencyBlobTimestamp() > 0) {
                Text(stringResource(R.string.storage_recency_reset))
            }

            extras.message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }

            ServerCleanupCard(status)
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
    if (confirmRecencyReset) {
        ConfirmClearDialog(
                title = stringResource(R.string.storage_recency_reset),
                text = stringResource(R.string.storage_recency_reset_confirm),
                onConfirm = { confirmRecencyReset = false; viewModel.resetRecencyBlob() },
                onDismiss = { confirmRecencyReset = false },
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

/** Server-managed cleanup: what still awaits a server decision, what the server had deleted, the last plan. */
@Composable
private fun ServerCleanupCard(status: DropboxStatus) {
    val control = status.control()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.storage_server_title), fontWeight = FontWeight.Bold)
            if (!control.available()) {
                Text(stringResource(R.string.storage_server_unavailable,
                        control.problem() ?: stringResource(R.string.value_unknown)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                return@Column
            }
            LabeledValue(stringResource(R.string.storage_server_pending_up),
                    stringResource(R.string.count_and_bytes, status.upstreamCount(),
                            StatusFormat.formatBytes(status.upstreamBytes())))
            LabeledValue(stringResource(R.string.storage_server_pending_down),
                    stringResource(R.string.count_and_bytes, status.downstreamCount(),
                            StatusFormat.formatBytes(status.downstreamBytes())))
            LabeledValue(stringResource(R.string.storage_server_deleted_up),
                    stringResource(R.string.count_and_bytes, control.deletedUpstreamCount().toInt(),
                            StatusFormat.formatBytes(control.deletedUpstreamBytes())))
            LabeledValue(stringResource(R.string.storage_server_deleted_down),
                    stringResource(R.string.count_and_bytes, control.deletedDownstreamCount().toInt(),
                            StatusFormat.formatBytes(control.deletedDownstreamBytes())))
            LabeledValue(stringResource(R.string.storage_server_last_plan),
                    if (control.lastPlanSeq() == 0L) stringResource(R.string.storage_server_no_plan)
                    else stringResource(R.string.storage_server_plan_value, control.lastPlanSeq(),
                            StatusFormat.formatAge(System.currentTimeMillis() - control.lastPlanAtMillis())))
            Text(stringResource(R.string.storage_server_text), style = MaterialTheme.typography.bodySmall)
        }
    }
}
