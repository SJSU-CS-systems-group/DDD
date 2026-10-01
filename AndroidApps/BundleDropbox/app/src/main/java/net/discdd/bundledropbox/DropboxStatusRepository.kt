package net.discdd.bundledropbox

import java.util.function.UnaryOperator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.discdd.bundledropbox.status.DropboxStatus

/**
 * Process-wide holder of the latest [DropboxStatus], the dropbox's equivalent of the transport's
 * `DDDWifiServiceEvents`. Java producers (service, cycle listener, RPC server, visit worker) publish
 * through the `@JvmStatic` functions; Compose screens and view models collect [status].
 *
 * A StateFlow (not a SharedFlow of events) because the UI only ever needs the latest snapshot and must
 * get it immediately when a screen subscribes.
 */
object DropboxStatusRepository {
    private val _status = MutableStateFlow(DropboxStatus.INITIAL)

    /** Latest status snapshot; starts at [DropboxStatus.INITIAL]. */
    @JvmStatic
    val status: StateFlow<DropboxStatus> = _status.asStateFlow()

    /** Replaces the whole snapshot. Prefer [modify] when other threads may update concurrently. */
    @JvmStatic
    fun update(s: DropboxStatus) {
        _status.value = s
    }

    /**
     * Atomically derives a new snapshot from the current one (compare-and-set loop), so concurrent
     * producers that each change different fields do not overwrite each other. From Java:
     * `DropboxStatusRepository.modify(s -> s.toBuilder().activeSessions(n).build())`.
     * The function may run more than once under contention, so it must be side-effect free.
     */
    @JvmStatic
    fun modify(fn: UnaryOperator<DropboxStatus>) {
        _status.update { fn.apply(it) }
    }

    /** @return the current snapshot, for Java callers that do not collect the flow. */
    @JvmStatic
    fun current(): DropboxStatus = _status.value
}
