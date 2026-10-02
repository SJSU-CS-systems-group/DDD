package net.discdd.bundledropbox.viewmodels

import androidx.annotation.StringRes
import net.discdd.bundledropbox.R
import net.discdd.bundledropbox.cycle.CycleMode
import net.discdd.bundledropbox.status.DropboxStatus
import java.util.Locale

/**
 * What the operator should read off the Status tab, independent of the internal [CycleMode] names.
 *
 * Several cycle modes collapse into one operator state (e.g. HOST_STARTING and VISIT_STOPPING both
 * mean "client service is coming back"), and one mode can split into two (VISITING is "searching"
 * until a carrier exchange holds the activity-gate token, then "exchanging").
 */
enum class OperatorState(@StringRes val labelRes: Int, @StringRes val explanationRes: Int) {
    ACCEPTING_CLIENTS(R.string.op_accepting_clients, R.string.op_accepting_clients_explain),
    FINISHING_CLIENT_TRANSFERS(R.string.op_finishing_client_transfers, R.string.op_finishing_client_transfers_explain),
    SWITCHING_TO_TRANSPORT(R.string.op_switching_to_transport, R.string.op_switching_to_transport_explain),
    SEARCHING_FOR_TRANSPORT(R.string.op_searching_for_transport, R.string.op_searching_for_transport_explain),
    EXCHANGING_WITH_TRANSPORT(R.string.op_exchanging_with_transport, R.string.op_exchanging_with_transport_explain),
    FINISHING_TRANSPORT_TRANSFER(R.string.op_finishing_transport_transfer, R.string.op_finishing_transport_transfer_explain),
    RESTORING_CLIENT_SERVICE(R.string.op_restoring_client_service, R.string.op_restoring_client_service_explain),
    WAITING_FOR_FIRST_VISIT(R.string.op_waiting_for_first_visit, R.string.op_waiting_for_first_visit_explain),
    PAUSED(R.string.op_paused, R.string.op_paused_explain),
    NOT_RUNNING(R.string.op_not_running, R.string.op_not_running_explain),
    NEEDS_ATTENTION(R.string.op_needs_attention, R.string.op_needs_attention_explain),
}

/**
 * Pure (Android-free apart from resource ids) presentation helpers for the dropbox screens, kept
 * out of the composables so they can be unit tested on the JVM (see `StatusFormatTest`).
 */
object StatusFormat {

    /**
     * Maps a status snapshot to the big operator-facing label on the Status tab.
     *
     * @param status latest [DropboxStatus] from `DropboxStatusRepository`
     * @param paused true if the operator paused the cycle (the controller reports STOPPED in that
     *               case, which is otherwise indistinguishable from "not started yet")
     */
    @JvmStatic
    fun operatorState(status: DropboxStatus, paused: Boolean): OperatorState {
        // Bootstrap (03 Architecture §1): with no recency blob the controller loops VISIT periods only,
        // because the stock client aborts its whole exchange without a SUCCESS recency blob (R1 §0.3,
        // R3). Searching in that phase is reported as "waiting for first transport visit" so the
        // operator knows clients cannot be served yet; an actual exchange still shows as such.
        val bootstrap = !status.hasRecencyBlob()
        val mode: CycleMode = status.mode() // non-null by contract (Builder requires it)
        return when (mode) {
            CycleMode.STOPPED -> if (paused) OperatorState.PAUSED else OperatorState.NOT_RUNNING
            CycleMode.HOST_STARTING -> OperatorState.RESTORING_CLIENT_SERVICE
            CycleMode.HOSTING -> OperatorState.ACCEPTING_CLIENTS
            CycleMode.HOST_DRAINING -> OperatorState.FINISHING_CLIENT_TRANSFERS
            CycleMode.HOST_STOPPING -> OperatorState.SWITCHING_TO_TRANSPORT
            CycleMode.VISIT_STARTING ->
                if (bootstrap) OperatorState.WAITING_FOR_FIRST_VISIT else OperatorState.SEARCHING_FOR_TRANSPORT
            // One activity-gate token covers a whole carrier exchange (03 Architecture "Visit-side
            // keying"), so activeSessions > 0 while VISITING means a carrier exchange is in progress.
            CycleMode.VISITING -> when {
                status.activeSessions() > 0 -> OperatorState.EXCHANGING_WITH_TRANSPORT
                bootstrap -> OperatorState.WAITING_FOR_FIRST_VISIT
                else -> OperatorState.SEARCHING_FOR_TRANSPORT
            }
            CycleMode.VISIT_DRAINING -> OperatorState.FINISHING_TRANSPORT_TRANSFER
            CycleMode.VISIT_STOPPING ->
                if (bootstrap) OperatorState.WAITING_FOR_FIRST_VISIT else OperatorState.RESTORING_CLIENT_SERVICE
            CycleMode.UNAVAILABLE -> OperatorState.NEEDS_ATTENTION
        }
    }

    /**
     * @param status  latest snapshot
     * @param nowMono `SystemClock.elapsedRealtime()` (the deadline is on the same monotonic clock)
     * @return milliseconds left until [DropboxStatus.deadlineAtMillis], clamped at 0, or `null` if
     *         the current mode has no deadline
     */
    @JvmStatic
    fun remainingMillis(status: DropboxStatus, nowMono: Long): Long? {
        if (!status.hasDeadline()) return null
        return (status.deadlineAtMillis() - nowMono).coerceAtLeast(0L)
    }

    /**
     * Fraction of the current timed mode that has elapsed, for the progress bar.
     *
     * @return 0..1, or `null` when there is no deadline or no usable start time (the UI then shows an
     *         indeterminate bar for transitional modes)
     */
    @JvmStatic
    fun elapsedFraction(status: DropboxStatus, nowMono: Long): Float? {
        if (!status.hasDeadline()) return null
        val start = status.modeStartedAtMillis()
        val total = status.deadlineAtMillis() - start
        if (start <= 0L || total <= 0L) return null
        return ((nowMono - start).toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    /** Formats a countdown as `m:ss` (or `h:mm:ss` past an hour). Negative values print as 0:00. */
    @JvmStatic
    fun formatCountdown(millis: Long): String {
        // Round up so a countdown never shows 0:00 while time is still left.
        val totalSeconds = (millis.coerceAtLeast(0L) + 999L) / 1000L
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /**
     * Human-readable age, e.g. `45 s`, `12 min`, `3 h`, `2 d`.
     *
     * @param ageMillis now minus a past wall-clock time; negative ages (the blob's server clock is
     *                  ahead of the dropbox's, which is common on an offline phone) print as `0 s`
     */
    @JvmStatic
    fun formatAge(ageMillis: Long): String {
        val s = ageMillis.coerceAtLeast(0L) / 1000L
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            s < 86_400 -> "${s / 3600} h"
            else -> "${s / 86_400} d"
        }
    }

    /** Formats a byte count with binary units (B, KB, MB, GB), one decimal above bytes. */
    @JvmStatic
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes / 1024.0
        var unit = 0
        while (value >= 1024.0 && unit < units.size - 1) {
            value /= 1024.0
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}
