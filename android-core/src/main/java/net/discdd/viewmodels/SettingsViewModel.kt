package net.discdd.viewmodels

import android.app.Application
import android.content.Context.MODE_PRIVATE
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsViewModel(
    application: Application
): AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val sharedPref = context.getSharedPreferences(NET_DISCDD_VIEWMODELS_PREFS, MODE_PRIVATE)

    private val _firstOpen = MutableStateFlow(true)
    val firstOpen = _firstOpen.asStateFlow()

    private val _showEasterEgg = MutableStateFlow(false)
    val showEasterEgg = _showEasterEgg.asStateFlow()

    init {
        _firstOpen.value = sharedPref.getBoolean(NET_DISCDD_VIEWMODELS_FIRST_OPEN, true)
        _showEasterEgg.value = sharedPref.getBoolean(NET_DISCDD_VIEWMODELS_SHOW_EASTER_EGG, false)
    }

    fun onFirstOpen() {
        _firstOpen.value = false
        sharedPref.edit().putBoolean(NET_DISCDD_VIEWMODELS_FIRST_OPEN, false).apply()
    }

    fun onToggleEasterEgg() {
        val newValue = !_showEasterEgg.value
        _showEasterEgg.value = newValue
        sharedPref.edit().putBoolean(NET_DISCDD_VIEWMODELS_SHOW_EASTER_EGG, newValue).apply()
    }

    companion object {
        const val NET_DISCDD_VIEWMODELS_PREFS = "net.discdd.viewmodels.PREFS"
        const val NET_DISCDD_VIEWMODELS_FIRST_OPEN = "net.discdd.viewmodels.FIRST_OPEN"
        const val NET_DISCDD_VIEWMODELS_SHOW_EASTER_EGG = "net.discdd.viewmodels.SHOW_EASTER_EGG"
    }
}
