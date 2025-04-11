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
    private val sharedPref = context.getSharedPreferences(NET_DISCDD_VIEWMODELS_FIRST_OPEN, MODE_PRIVATE)
    private val _firstOpen = MutableStateFlow(true)
    val firstOpen = _firstOpen.asStateFlow()

    init {
        val firstOpenCached = sharedPref.getBoolean(NET_DISCDD_VIEWMODELS_FIRST_OPEN, true)
        _firstOpen.value = firstOpenCached
    }

    fun onFirstOpen() {
        _firstOpen.value = false
        sharedPref.edit().putBoolean(NET_DISCDD_VIEWMODELS_FIRST_OPEN, false).apply()
    }

    companion object {
        const val NET_DISCDD_VIEWMODELS_FIRST_OPEN: String = "net.discdd.viewmodels.FIRST_OPEN"
    }
}
