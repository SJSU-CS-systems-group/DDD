package net.discdd.bundledropbox.viewmodels

import android.app.Application
import android.content.SharedPreferences
import android.os.StatFs
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.discdd.bundledropbox.DropboxControls
import net.discdd.bundledropbox.DropboxPrefs
import net.discdd.bundledropbox.DropboxServiceManager
import net.discdd.bundledropbox.DropboxStatusRepository
import net.discdd.bundledropbox.R
import net.discdd.bundledropbox.status.DropboxStatus
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Device-level storage numbers that do not come from [DropboxStatus].
 *
 * @property capacityBytes  configured store capacity (`max_storage_mb` setting)
 * @property deviceFreeBytes free bytes on the volume holding the app's files (0 if unknown)
 * @property message        transient result of a clear action, shown for a few seconds
 */
data class StorageExtras(
        val capacityBytes: Long = DropboxPrefs.DEFAULT_MAX_STORAGE_MB * 1024L * 1024L,
        val deviceFreeBytes: Long = 0L,
        val message: String? = null,
)

/**
 * Backs the Storage tab. Per-direction counts and bytes come from [DropboxStatusRepository] (the
 * service publishes `RelayStore.stats()` into it), the capacity from [DropboxPrefs], and the two
 * operator "clear" actions go to the service.
 *
 * v1 never deletes bundles on its own (KeepAllRetentionPolicy); the clear buttons are the only
 * deletion path. FUTURE: server keep/delete control ADUs drive `RetentionPolicy` + `RelayStore.delete`
 * and the clear buttons become a last-resort override.
 */
class StorageViewModel(application: Application) : AndroidViewModel(application) {
    private val logger = Logger.getLogger(StorageViewModel::class.java.name)
    private val context get() = getApplication<Application>()
    private val prefs: SharedPreferences = DropboxPrefs.get(application)

    /** Latest status snapshot (counts/bytes per direction). */
    val status: StateFlow<DropboxStatus> = DropboxStatusRepository.status

    private val _extras = MutableStateFlow(StorageExtras())
    val extras: StateFlow<StorageExtras> = _extras.asStateFlow()

    // Keeps the capacity line in sync when the Settings tab changes max_storage_mb.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == DropboxPrefs.KEY_MAX_STORAGE_MB) refresh()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        refresh()
    }

    /** Re-reads capacity and device free space (off the main thread). */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val free = try {
                StatFs(context.filesDir.path).availableBytes
            } catch (e: IllegalArgumentException) {
                0L
            }
            _extras.update { it.copy(capacityBytes = DropboxPrefs.maxStorageBytes(prefs), deviceFreeBytes = free) }
        }
    }

    /** Deletes all deposited (UPSTREAM, client-to-server) bundles via the service. */
    fun clearUpstream() = clear(R.string.storage_cleared_upstream) { it.clearUpstream() }

    /** Deletes all pickups (DOWNSTREAM, server-to-client) bundles via the service. */
    fun clearDownstream() = clear(R.string.storage_cleared_downstream) { it.clearDownstream() }

    fun clearMessage() = _extras.update { it.copy(message = null) }

    private fun clear(@StringRes doneMessage: Int, action: (DropboxControls) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val controls = DropboxServiceManager.controls()
            val msg = if (controls == null) {
                context.getString(R.string.service_not_connected)
            } else {
                try {
                    action(controls)
                    context.getString(doneMessage)
                } catch (e: Exception) {
                    logger.log(Level.WARNING, "Clear failed", e)
                    context.getString(R.string.storage_clear_failed, e.message ?: e.javaClass.simpleName)
                }
            }
            _extras.update { it.copy(message = msg) }
            refresh()
        }
    }

    override fun onCleared() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onCleared()
    }
}
