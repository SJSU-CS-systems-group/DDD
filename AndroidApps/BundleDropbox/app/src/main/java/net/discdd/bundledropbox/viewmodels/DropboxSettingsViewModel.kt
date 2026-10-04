package net.discdd.bundledropbox.viewmodels

import android.app.Application
import android.content.SharedPreferences
import androidx.core.content.edit
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
 * Editable form state of the Settings tab. Numeric fields are kept as the raw text the operator
 * typed, so a half-typed value never gets clobbered by re-formatting; they are parsed and validated
 * only on Save ([SettingsValidation]).
 */
data class SettingsForm(
        val clientPeriodS: String = DropboxPrefs.DEFAULT_CLIENT_PERIOD_S.toString(),
        val transportPeriodS: String = DropboxPrefs.DEFAULT_TRANSPORT_PERIOD_S.toString(),
        val drainCapS: String = DropboxPrefs.DEFAULT_DRAIN_CAP_S.toString(),
        val maxStorageMb: String = DropboxPrefs.DEFAULT_MAX_STORAGE_MB.toString(),
        val returnAfterVisit: Boolean = DropboxPrefs.DEFAULT_RETURN_AFTER_VISIT,
        val rotateAdvertisedId: Boolean = DropboxPrefs.DEFAULT_ROTATE_ADVERTISED_ID,
        val keepAwake: Boolean = DropboxPrefs.DEFAULT_KEEP_AWAKE,
        val startOnBoot: Boolean = DropboxPrefs.DEFAULT_START_ON_BOOT,
        /** true when the numeric fields differ from what is stored. */
        val dirty: Boolean = false,
        /** pref key -> @StringRes error text, for fields that failed validation on the last Save. */
        val errors: Map<String, Int> = emptyMap(),
        /** transient "Saved" confirmation. */
        val saved: Boolean = false,
)

/**
 * State of the Settings tab's "Server keys (QR)" section.
 *
 * @property url      the pasted (or last scanned) server URL
 * @property scanning true while the camera QR scanner is shown
 * @property busy     true while an import or reset runs in the service
 * @property message  result of the last import or reset, or `null`
 * @property failed   true if [message] reports a failure
 */
data class ServerKeysForm(
        val url: String = "",
        val scanning: Boolean = false,
        val busy: Boolean = false,
        val message: String? = null,
        val failed: Boolean = false,
)

/**
 * Validation rules for the numeric settings, kept pure for JVM tests. Ranges are deliberately
 * generous (this is a test-bench app) but reject values that would break the cycle:
 * dwell timers must be at least 1 s (CycleConfig requires dwell > 0), the drain cap may be 0
 * ("switch immediately, cancel in-flight work"), capacity at least 1 MB. Upper bounds stop typos
 * such as an extra zero turning a 60 s dwell into hours.
 */
object SettingsValidation {
    const val MIN_DWELL_S = 1
    const val MAX_DWELL_S = 24 * 3600
    const val MIN_DRAIN_S = 0
    const val MAX_DRAIN_S = 3600
    const val MIN_STORAGE_MB = 1
    const val MAX_STORAGE_MB = 1024 * 1024

    /** @return the parsed value if [text] is an integer in `min..max`, else `null`. */
    @JvmStatic
    fun parseInRange(text: String, min: Int, max: Int): Int? = text.trim().toIntOrNull()?.takeIf { it in min..max }
}

/**
 * Backs the Settings tab: loads and saves the dropbox's [DropboxPrefs] (the same file and keys
 * `DropboxService` reads). Following the transport's SharedPreferences -> service-listener pattern
 * (R5 §5.5 item 4), saving only writes preferences; the service's `OnSharedPreferenceChangeListener`
 * pushes the new `CycleConfig` into the role-cycle controller (`updateConfig`) and applies the
 * advertised-ID / keep-awake / capacity settings. The UI never talks to the controller directly.
 *
 * Switches are written immediately; numeric fields are written together on Save after validation.
 *
 * Server keys are not preferences: an import goes straight to the service ([DropboxControls.importServerKeys]),
 * which saves the keys and re-wires the recency check and the control endpoint. The keys currently trusted
 * are read back from [DropboxStatus.control].
 */
class DropboxSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val logger = Logger.getLogger(DropboxSettingsViewModel::class.java.name)
    private val prefs: SharedPreferences = DropboxPrefs.get(application)

    /** Latest status; the Settings tab shows `control().serverKeys()` from it. */
    val status: StateFlow<DropboxStatus> = DropboxStatusRepository.status

    private val _serverKeys = MutableStateFlow(ServerKeysForm())
    val serverKeys: StateFlow<ServerKeysForm> = _serverKeys.asStateFlow()

    private val _form = MutableStateFlow(SettingsForm())
    val form: StateFlow<SettingsForm> = _form.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { load() }
    }

    private fun load() {
        _form.value = SettingsForm(
                clientPeriodS = prefs.getInt(DropboxPrefs.KEY_CLIENT_PERIOD_S, DropboxPrefs.DEFAULT_CLIENT_PERIOD_S).toString(),
                transportPeriodS = prefs.getInt(DropboxPrefs.KEY_TRANSPORT_PERIOD_S, DropboxPrefs.DEFAULT_TRANSPORT_PERIOD_S).toString(),
                drainCapS = prefs.getInt(DropboxPrefs.KEY_DRAIN_CAP_S, DropboxPrefs.DEFAULT_DRAIN_CAP_S).toString(),
                maxStorageMb = prefs.getInt(DropboxPrefs.KEY_MAX_STORAGE_MB, DropboxPrefs.DEFAULT_MAX_STORAGE_MB).toString(),
                returnAfterVisit = prefs.getBoolean(DropboxPrefs.KEY_RETURN_AFTER_VISIT, DropboxPrefs.DEFAULT_RETURN_AFTER_VISIT),
                rotateAdvertisedId = prefs.getBoolean(DropboxPrefs.KEY_ROTATE_ADVERTISED_ID, DropboxPrefs.DEFAULT_ROTATE_ADVERTISED_ID),
                keepAwake = prefs.getBoolean(DropboxPrefs.KEY_KEEP_AWAKE, DropboxPrefs.DEFAULT_KEEP_AWAKE),
                startOnBoot = prefs.getBoolean(DropboxPrefs.KEY_START_ON_BOOT, DropboxPrefs.DEFAULT_START_ON_BOOT),
        )
    }

    fun onClientPeriodChange(v: String) = _form.update { it.copy(clientPeriodS = v, dirty = true, saved = false) }
    fun onTransportPeriodChange(v: String) = _form.update { it.copy(transportPeriodS = v, dirty = true, saved = false) }
    fun onDrainCapChange(v: String) = _form.update { it.copy(drainCapS = v, dirty = true, saved = false) }
    fun onMaxStorageChange(v: String) = _form.update { it.copy(maxStorageMb = v, dirty = true, saved = false) }

    fun onReturnAfterVisitChange(v: Boolean) = putBoolean(DropboxPrefs.KEY_RETURN_AFTER_VISIT, v) { it.copy(returnAfterVisit = v) }
    fun onRotateAdvertisedIdChange(v: Boolean) = putBoolean(DropboxPrefs.KEY_ROTATE_ADVERTISED_ID, v) { it.copy(rotateAdvertisedId = v) }
    fun onKeepAwakeChange(v: Boolean) = putBoolean(DropboxPrefs.KEY_KEEP_AWAKE, v) { it.copy(keepAwake = v) }
    fun onStartOnBootChange(v: Boolean) = putBoolean(DropboxPrefs.KEY_START_ON_BOOT, v) { it.copy(startOnBoot = v) }

    private fun putBoolean(key: String, value: Boolean, change: (SettingsForm) -> SettingsForm) {
        _form.update(change)
        viewModelScope.launch(Dispatchers.IO) { prefs.edit { putBoolean(key, value) } }
    }

    /** Validates the numeric fields; writes all four in one commit if every field is valid. */
    fun save() {
        val f = _form.value
        val client = SettingsValidation.parseInRange(f.clientPeriodS, SettingsValidation.MIN_DWELL_S, SettingsValidation.MAX_DWELL_S)
        val transport = SettingsValidation.parseInRange(f.transportPeriodS, SettingsValidation.MIN_DWELL_S, SettingsValidation.MAX_DWELL_S)
        val drain = SettingsValidation.parseInRange(f.drainCapS, SettingsValidation.MIN_DRAIN_S, SettingsValidation.MAX_DRAIN_S)
        val storage = SettingsValidation.parseInRange(f.maxStorageMb, SettingsValidation.MIN_STORAGE_MB, SettingsValidation.MAX_STORAGE_MB)

        val errors = buildMap {
            if (client == null) put(DropboxPrefs.KEY_CLIENT_PERIOD_S, R.string.settings_error_dwell)
            if (transport == null) put(DropboxPrefs.KEY_TRANSPORT_PERIOD_S, R.string.settings_error_dwell)
            if (drain == null) put(DropboxPrefs.KEY_DRAIN_CAP_S, R.string.settings_error_drain)
            if (storage == null) put(DropboxPrefs.KEY_MAX_STORAGE_MB, R.string.settings_error_storage)
        }
        if (client == null || transport == null || drain == null || storage == null) {
            _form.update { it.copy(errors = errors, saved = false) }
            return
        }
        _form.update { it.copy(errors = emptyMap(), dirty = false, saved = true) }
        viewModelScope.launch(Dispatchers.IO) {
            // One edit -> one batch of listener callbacks; the service rebuilds CycleConfig from all keys.
            prefs.edit {
                putInt(DropboxPrefs.KEY_CLIENT_PERIOD_S, client)
                putInt(DropboxPrefs.KEY_TRANSPORT_PERIOD_S, transport)
                putInt(DropboxPrefs.KEY_DRAIN_CAP_S, drain)
                putInt(DropboxPrefs.KEY_MAX_STORAGE_MB, storage)
            }
        }
    }

    /** Discards unsaved numeric edits. */
    fun revert() {
        viewModelScope.launch(Dispatchers.IO) { load() }
    }

    /** Restores every setting to its default and saves. */
    fun resetToDefaults() {
        _form.value = SettingsForm(saved = true)
        viewModelScope.launch(Dispatchers.IO) {
            prefs.edit {
                putInt(DropboxPrefs.KEY_CLIENT_PERIOD_S, DropboxPrefs.DEFAULT_CLIENT_PERIOD_S)
                putInt(DropboxPrefs.KEY_TRANSPORT_PERIOD_S, DropboxPrefs.DEFAULT_TRANSPORT_PERIOD_S)
                putInt(DropboxPrefs.KEY_DRAIN_CAP_S, DropboxPrefs.DEFAULT_DRAIN_CAP_S)
                putInt(DropboxPrefs.KEY_MAX_STORAGE_MB, DropboxPrefs.DEFAULT_MAX_STORAGE_MB)
                putBoolean(DropboxPrefs.KEY_RETURN_AFTER_VISIT, DropboxPrefs.DEFAULT_RETURN_AFTER_VISIT)
                putBoolean(DropboxPrefs.KEY_ROTATE_ADVERTISED_ID, DropboxPrefs.DEFAULT_ROTATE_ADVERTISED_ID)
                putBoolean(DropboxPrefs.KEY_KEEP_AWAKE, DropboxPrefs.DEFAULT_KEEP_AWAKE)
                putBoolean(DropboxPrefs.KEY_START_ON_BOOT, DropboxPrefs.DEFAULT_START_ON_BOOT)
            }
        }
    }

    fun clearSaved() = _form.update { it.copy(saved = false) }

    fun onServerUrlChange(v: String) = _serverKeys.update { it.copy(url = v, message = null) }

    fun startScan() = _serverKeys.update { it.copy(scanning = true, message = null) }

    fun stopScan() = _serverKeys.update { it.copy(scanning = false) }

    /** A QR code was scanned: show its text and import it at once (the scanner reports one code per opening). */
    fun onScanned(text: String) {
        _serverKeys.update { it.copy(url = text, scanning = false) }
        importServerKeys()
    }

    /** Imports the keys of the URL in the text field. */
    fun importServerKeys() {
        val url = _serverKeys.value.url
        changeServerKeys("importServerKeys") { it.importServerKeys(url) }
    }

    /** Goes back to android-core's built-in server keys. */
    fun useBuiltInServerKeys() = changeServerKeys("useBuiltInServerKeys") { it.useBuiltInServerKeys() }

    private fun changeServerKeys(name: String, action: (DropboxControls) -> String) {
        _serverKeys.update { it.copy(busy = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val controls = DropboxServiceManager.controls()
            val (msg, failed) = if (controls == null) {
                app.getString(R.string.service_not_connected) to true
            } else {
                try {
                    app.getString(R.string.settings_server_keys_done, action(controls)) to false
                } catch (e: Exception) {
                    logger.log(Level.WARNING, "$name failed", e)
                    app.getString(R.string.settings_server_keys_failed, e.message ?: e.javaClass.simpleName) to true
                }
            }
            _serverKeys.update { it.copy(busy = false, message = msg, failed = failed) }
        }
    }
}
