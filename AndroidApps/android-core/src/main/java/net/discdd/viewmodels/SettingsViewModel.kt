package net.discdd.viewmodels

import android.app.Application
import android.content.Context.MODE_PRIVATE
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    application: Application
) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val sharedPref by lazy {
        context.getSharedPreferences(NET_DISCDD_VIEWMODELS_PREFS, MODE_PRIVATE)
    }

    private val _firstOpen = MutableStateFlow<Boolean>(true)
    val firstOpen = _firstOpen.asStateFlow()

    private val _showEasterEgg = MutableStateFlow(false)
    val showEasterEgg = _showEasterEgg.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _firstOpen.value = sharedPref.getBoolean(NET_DISCDD_VIEWMODELS_FIRST_OPEN, true)
            _showEasterEgg.value = sharedPref.getBoolean(NET_DISCDD_VIEWMODELS_SHOW_EASTER_EGG, false)
        }
    }

    fun onFirstOpen() {
        viewModelScope.launch(Dispatchers.IO) {
            _firstOpen.value = false
            sharedPref.edit { putBoolean(NET_DISCDD_VIEWMODELS_FIRST_OPEN, false) }
        }
    }

    fun onToggleEasterEgg() {
        viewModelScope.launch(Dispatchers.IO) {
            val newValue = !_showEasterEgg.value
            _showEasterEgg.value = newValue
            sharedPref.edit { putBoolean(NET_DISCDD_VIEWMODELS_SHOW_EASTER_EGG, newValue) }
        }
    }

    companion object {
        const val NET_DISCDD_VIEWMODELS_PREFS = "net.discdd.viewmodels.PREFS"
        const val NET_DISCDD_VIEWMODELS_FIRST_OPEN = "net.discdd.viewmodels.FIRST_OPEN"
        const val NET_DISCDD_VIEWMODELS_SHOW_EASTER_EGG = "net.discdd.viewmodels.SHOW_EASTER_EGG"
    }
}
