package net.discdd.bundledropbox.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import net.discdd.bundledropbox.DropboxControls
import net.discdd.bundledropbox.DropboxServiceManager
import net.discdd.bundledropbox.DropboxStatusRepository
import net.discdd.bundledropbox.status.DropboxStatus
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Backs the Status tab: exposes the latest [DropboxStatus] plus the operator "paused" flag, and
 * forwards Pause/Resume, "Visit transport now" and "Host now" to the service.
 *
 * Every service call runs on `Dispatchers.IO` (see [DropboxControls] threading note). The countdown
 * ticker lives in the composable, not here, so the view model holds no timers.
 */
class StatusViewModel(application: Application) : AndroidViewModel(application) {
    private val logger = Logger.getLogger(StatusViewModel::class.java.name)

    /** Latest snapshot from the service; collected directly from the process-wide repository. */
    val status: StateFlow<DropboxStatus> = DropboxStatusRepository.status

    /** true while the activity is bound to the service; gates the action buttons. */
    val serviceConnected: StateFlow<Boolean> = DropboxServiceManager.connected

    private val _paused = MutableStateFlow(false)

    /**
     * Operator pause flag. CycleMode has no PAUSED state (pause = STOPPED), so it is read back from
     * the service ([DropboxControls.isCyclePaused]) on every status/bind change, and set optimistically
     * when the operator taps the button.
     */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            combine(status, serviceConnected) { _, _ -> Unit }.collect {
                DropboxServiceManager.controls()?.let { c ->
                    runCatching { c.isCyclePaused() }.onSuccess { _paused.value = it }
                }
            }
        }
    }

    fun pause() = call("pauseCycle") { it.pauseCycle(); _paused.value = true }

    fun resume() = call("resumeCycle") { it.resumeCycle(); _paused.value = false }

    fun visitTransportNow() = call("forceVisitNow") { it.forceVisitNow() }

    fun hostNow() = call("forceHostNow") { it.forceHostNow() }

    private fun call(name: String, action: (DropboxControls) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val controls = DropboxServiceManager.controls()
            if (controls == null) {
                logger.warning("$name ignored: dropbox service not bound")
                return@launch
            }
            try {
                action(controls)
            } catch (e: Exception) {
                logger.log(Level.WARNING, "$name failed", e)
            }
        }
    }
}
