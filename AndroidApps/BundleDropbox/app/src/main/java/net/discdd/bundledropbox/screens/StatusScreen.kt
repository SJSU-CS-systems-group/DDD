package net.discdd.bundledropbox.screens

import android.os.SystemClock
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import net.discdd.bundledropbox.R
import net.discdd.bundledropbox.cycle.CycleMode
import net.discdd.bundledropbox.status.DropboxStatus
import net.discdd.bundledropbox.viewmodels.OperatorState
import net.discdd.bundledropbox.viewmodels.StatusFormat
import net.discdd.bundledropbox.viewmodels.StatusViewModel
import net.discdd.components.EasterEgg
import java.util.Date

/**
 * Status tab: the operator's at-a-glance view of the role cycle.
 *
 * Shows a large operator label ([OperatorState]), a live countdown to the mode deadline with a progress
 * bar, identity / recency-blob / queue details, the server-managed storage (control channel) state, the
 * last visit, the last error, and the cycle controls
 * (Pause/Resume, Visit now, Host now, and Stop/Start for the whole relay).
 * Like the transport's Upload tab, the mode card is wrapped in android-core's [EasterEgg] (7 taps in
 * 3 s) to toggle the admin tabs.
 *
 * Only counters and identifiers are shown; never bundle contents (they are opaque to the dropbox).
 *
 * @param onToggleEasterEgg invoked when the easter egg fires (home screen toggles admin tabs)
 */
@Composable
fun StatusScreen(
        viewModel: StatusViewModel = viewModel(),
        onToggleEasterEgg: () -> Unit = {},
) {
    val status by viewModel.status.collectAsState()
    val paused by viewModel.paused.collectAsState()
    val connected by viewModel.serviceConnected.collectAsState()
    val stopped by viewModel.relayStopped.collectAsState()
    var confirmStop by remember { mutableStateOf(false) }

    // 1 s ticker for the countdown. DropboxStatus deadlines are on the monotonic
    // SystemClock.elapsedRealtime() clock (the cycle Clock), so "now" must be read from the same clock;
    // keyed on status so a new deadline is reflected immediately rather than on the next tick.
    val nowMono by produceState(SystemClock.elapsedRealtime(), status) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }

    val op = StatusFormat.operatorState(status, paused, stopped)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
                modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EasterEgg(content = { ModeCard(status, op, nowMono) }, onToggle = onToggleEasterEgg)

            if (stopped) {
                // A stopped relay has no running cycle to control; Start goes through a start command,
                // so it does not need a bound service.
                Button(onClick = viewModel::startRelay, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.status_start_relay))
                }
            } else {
                CycleControls(
                        status = status,
                        paused = paused,
                        enabled = connected,
                        onPause = viewModel::pause,
                        onResume = viewModel::resume,
                        onVisitNow = viewModel::visitTransportNow,
                        onHostNow = viewModel::hostNow,
                )
            }

            DetailsCard(status)
            ControlCard(status)
            // FUTURE: once server destination manifests replace download-all, show the manifest scope here.
            LastVisitCard(status.lastVisit())

            status.lastError()?.let { err ->
                Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.status_last_error), fontWeight = FontWeight.Bold)
                        Text(err)
                    }
                }
            }

            if (!stopped) {
                // Last on the screen and behind a confirmation: it ends the relay until Start is pressed.
                TextButton(onClick = { confirmStop = true }, enabled = connected, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.status_stop_relay), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
                title = { Text(stringResource(R.string.status_confirm_stop_title)) },
                text = { Text(stringResource(R.string.status_confirm_stop)) },
                onDismissRequest = { confirmStop = false },
                confirmButton = {
                    TextButton(onClick = {
                        confirmStop = false
                        viewModel.stopRelay()
                    }) {
                        Text(stringResource(R.string.status_confirm_stop_ok), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.cancel)) }
                },
        )
    }
}

/** Big label, explanation, countdown and progress bar for the current mode. */
@Composable
private fun ModeCard(status: DropboxStatus, op: OperatorState, nowMono: Long) {
    val remaining = StatusFormat.remainingMillis(status, nowMono)
    val fraction = StatusFormat.elapsedFraction(status, nowMono)
    val container = when (op) {
        OperatorState.NEEDS_ATTENTION -> MaterialTheme.colorScheme.errorContainer
        OperatorState.WAITING_FOR_FIRST_VISIT, OperatorState.PAUSED, OperatorState.NOT_RUNNING ->
            MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                    text = stringResource(op.labelRes),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
            )
            Text(text = stringResource(op.explanationRes), style = MaterialTheme.typography.bodyMedium)
            if (remaining != null) {
                Text(
                        text = stringResource(deadlineLabel(status.mode()), StatusFormat.formatCountdown(remaining)),
                        style = MaterialTheme.typography.titleLarge,
                )
            }
            when {
                fraction != null -> LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                // Radio preparation/teardown never consumes dwell time and has no deadline: show activity only.
                isTransitional(status.mode()) -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            // Raw mode name for testers correlating with logs.
            Text(
                    text = stringResource(R.string.status_cycle_mode, status.mode().name),
                    style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun isTransitional(mode: CycleMode) = when (mode) {
    CycleMode.HOST_STARTING, CycleMode.HOST_STOPPING, CycleMode.VISIT_STARTING, CycleMode.VISIT_STOPPING -> true
    else -> false
}

/** What the deadline means in each mode (see CycleListener.onModeChanged). */
private fun deadlineLabel(mode: CycleMode): Int = when {
    mode.isDwelling() -> R.string.status_dwell_left
    mode.isDraining() -> R.string.status_drain_left
    mode == CycleMode.UNAVAILABLE -> R.string.status_retry_in
    else -> R.string.status_time_left
}

@Composable
private fun CycleControls(
        status: DropboxStatus,
        paused: Boolean,
        enabled: Boolean,
        onPause: () -> Unit,
        onResume: () -> Unit,
        onVisitNow: () -> Unit,
        onHostNow: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (paused) {
            Button(onClick = onResume, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.status_resume))
            }
        } else {
            OutlinedButton(onClick = onPause, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.status_pause))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                    onClick = onVisitNow,
                    enabled = enabled && !paused,
                    modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.status_visit_now)) }
            // Hosting is pointless without a recency blob: stock clients abort the exchange without a
            // SUCCESS blob (R1 §0.3), and the controller skips HOST in bootstrap anyway.
            OutlinedButton(
                    onClick = onHostNow,
                    enabled = enabled && !paused && status.hasRecencyBlob(),
                    modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.status_host_now)) }
        }
        if (!enabled) {
            Text(stringResource(R.string.service_not_connected), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Identity, recency blob, sessions and queue counters. */
@Composable
private fun DetailsCard(status: DropboxStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LabeledValue(stringResource(R.string.status_advertised_id),
                    status.advertisedId() ?: stringResource(R.string.value_unknown))
            LabeledValue(stringResource(R.string.status_recency_blob), recencyBlobText(status))
            LabeledValue(stringResource(R.string.status_active_sessions), status.activeSessions().toString())
            LabeledValue(stringResource(R.string.status_upstream),
                    stringResource(R.string.count_and_bytes, status.upstreamCount(),
                            StatusFormat.formatBytes(status.upstreamBytes())))
            LabeledValue(stringResource(R.string.status_downstream),
                    stringResource(R.string.count_and_bytes, status.downstreamCount(),
                            StatusFormat.formatBytes(status.downstreamBytes())))
        }
    }
}

/**
 * The dropbox's own control endpoint: its client id at the server, the trusted keys, the newest inventory
 * report and retention plan, and how many held bundles still wait for the server's decision (all of them:
 * nothing is deleted without a plan).
 */
@Composable
private fun ControlCard(status: DropboxStatus) {
    val control = status.control()
    val now = System.currentTimeMillis()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.status_control_title), fontWeight = FontWeight.Bold)
            if (control.serverKeys().isNotEmpty()) {
                LabeledValue(stringResource(R.string.status_control_server_keys), control.serverKeys())
            }
            if (!control.available()) {
                Text(stringResource(R.string.status_control_unavailable), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                control.problem()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                return@Column
            }
            LabeledValue(stringResource(R.string.status_control_client_id),
                    control.endpointClientId() ?: stringResource(R.string.value_unknown))
            LabeledValue(stringResource(R.string.status_control_last_report),
                    seqAge(control.lastReportSeq(), control.lastReportAtMillis(), now))
            LabeledValue(stringResource(R.string.status_control_last_plan),
                    seqAge(control.lastPlanSeq(), control.lastPlanAtMillis(), now))
            LabeledValue(stringResource(R.string.status_control_waiting),
                    stringResource(R.string.status_control_waiting_value, status.upstreamCount(),
                            status.downstreamCount()))
        }
    }
}

@Composable
private fun seqAge(seq: Long, atMillis: Long, now: Long): String =
        if (seq == 0L) stringResource(R.string.status_control_none_yet)
        else stringResource(R.string.status_control_seq_age, seq, StatusFormat.formatAge(now - atMillis))

@Composable
private fun recencyBlobText(status: DropboxStatus): String {
    if (!status.hasRecencyBlob()) return stringResource(R.string.status_recency_blob_none)
    val context = LocalContext.current
    val ts = status.recencyBlobTimestamp()
    // blobTimestamp is the server's wall clock; the age is relative to the dropbox's wall clock, which
    // may be skewed on an offline phone (StatusFormat.formatAge clamps negative ages to 0).
    val age = StatusFormat.formatAge(System.currentTimeMillis() - ts)
    val whenText = DateFormat.getDateFormat(context).format(Date(ts)) + " " +
            DateFormat.getTimeFormat(context).format(Date(ts))
    return stringResource(R.string.status_recency_blob_age, age, whenText)
}

@Composable
private fun LastVisitCard(visit: DropboxStatus.LastVisit?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.status_last_visit), fontWeight = FontWeight.Bold)
            if (visit == null) {
                Text(stringResource(R.string.status_last_visit_none))
                return@Column
            }
            val context = LocalContext.current
            LabeledValue(stringResource(R.string.status_carrier), visit.carrierId() ?: stringResource(R.string.value_unknown))
            LabeledValue(stringResource(R.string.status_visit_result),
                    stringResource(if (visit.ok()) R.string.status_visit_ok else R.string.status_visit_failed),
                    valueColor = if (visit.ok()) Color.Unspecified else MaterialTheme.colorScheme.error)
            LabeledValue(stringResource(R.string.status_visit_uploaded), visit.uploaded().toString())
            LabeledValue(stringResource(R.string.status_visit_downloaded), visit.downloaded().toString())
            LabeledValue(stringResource(R.string.status_visit_ended),
                    stringResource(R.string.status_ago, StatusFormat.formatAge(System.currentTimeMillis() - visit.endedAtMillis())) +
                            " (" + DateFormat.getTimeFormat(context).format(Date(visit.endedAtMillis())) + ")")
        }
    }
}

/** One "label: value" row, shared by the Status, Local Wifi and Storage tabs. */
@Composable
internal fun LabeledValue(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                color = valueColor, modifier = Modifier.padding(start = 8.dp))
    }
}
