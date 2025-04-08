package net.discdd.bundleclient

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsManager(context: Context) {
    private val sharedPreferences = context.getSharedPreferences(
        "net.discdd.bundleclient.settings",
        Context.MODE_PRIVATE
    )

    private val _numDenied = MutableStateFlow(getNumDenied())
    val numDenied = _numDenied.asStateFlow()

    private val _initialOpen = MutableStateFlow(getInitialOpen())
    val initialOpen = _initialOpen.asStateFlow()

    private fun getNumDenied(): Int {
        return sharedPreferences.getInt(KEY_NUM_DENIED, 0)
    }

    fun incrementNumDenied() {
        val newValue = getNumDenied() + 1
        sharedPreferences.edit().putInt(KEY_NUM_DENIED, newValue).apply()
        _numDenied.value = newValue
    }

    private fun getInitialOpen(): Boolean {
        return sharedPreferences.getBoolean(KEY_INITIAL_OPEN, false)
    }

    fun setInitialOpen(open: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_INITIAL_OPEN, open).apply()
        _initialOpen.value = open
    }

    companion object {
        private const val KEY_NUM_DENIED = "net.discdd.bundleclient.NUM_DENIED"
        private const val KEY_INITIAL_OPEN = "net.discdd.bundleclient.INITIAL_OPEN"
    }
}