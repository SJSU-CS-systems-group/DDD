package net.discdd.bundletransport.viewmodels

import android.app.Application
import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.discdd.bundletransport.R
import net.discdd.bundletransport.StorageManager

data class StorageState(
    val sliderValue: Int = 0,
    val freeSpace: Int = 0,
    val usedSpace: Int = 0,
    val totalBytes: Int = 0,
    val actualStorageValue: Int = 0,
    val showMessage: String? = null
)

class StorageViewModel(application: Application) : AndroidViewModel(application) {
    private val minStorage = 100
    private val context get() = getApplication<Application>()
    private val storageManager = StorageManager(
        context.getExternalFilesDir(null)?.toPath(),
        retrievePreference().toLong()
    )
    private val _state = MutableStateFlow(StorageState())
    val state = _state.asStateFlow()

    init {
        updateStorageInfo()
    }

    private fun updateStorageInfo() {
        viewModelScope.launch {
            try {
                val totalBytes = getTotalBytes()
                val usedBytes = getUsedBytes()
                _state.update { it.copy(
                    usedSpace = usedBytes,
                    freeSpace = totalBytes - usedBytes,
                    totalBytes = totalBytes,
                    actualStorageValue = minStorage + it.sliderValue
                )}
            } catch (e: Exception) {
                _state.update { it.copy(showMessage = context.getString(R.string.error_updating_storage_info)) }
            }
        }
    }

    fun onSliderValueChange(value: Int) {
        _state.update { it.copy(
            sliderValue = value,
            actualStorageValue = minStorage + value,
            showMessage = context.getString(R.string.apply_changes)
        )}
    }

    fun onSetStorageClick() {
        viewModelScope.launch {
            try {
                storageManager.setUserStoragePreference(_state.value.sliderValue)
                storageManager.updateStorage()
                updateStorageInfo()
                savePreference(_state.value.sliderValue)
                _state.update { it.copy(showMessage = context.getString(R.string.storage_updated)) }
            } catch (e: Exception) {
                _state.update { it.copy(showMessage = context.getString(R.string.failed_to_update_storage)) }
            }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(showMessage = null) }
    }

    private fun getUsedBytes(): Int {
        return try {
            val totalFiles = storageManager.getStorageList()
            storageManager.getStorageSize(totalFiles).toInt()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    private fun savePreference(value: Int) {
        context.getSharedPreferences("SeekBarPrefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("seekBarPosition", value)
            .apply()
    }

    private fun retrievePreference(): Int {
        return context.getSharedPreferences("SeekBarPrefs", Context.MODE_PRIVATE)
            .getInt("seekBarPosition", 0)
    }

    private fun getTotalBytes(): Int {
        return StatFs(Environment.getExternalStorageDirectory().path)
            .totalBytes.toInt()
            .div(1024 * 1024)
    }
}