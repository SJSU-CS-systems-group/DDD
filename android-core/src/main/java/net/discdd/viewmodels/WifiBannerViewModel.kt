package net.discdd.viewmodels

import android.app.Application
import android.content.Context.MODE_PRIVATE
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
Viewmodels for screens that require functionality to NEARBY_WIFI_DEVICES will extend this class.
Ensure that the screens include the WifiPermissionBanner which prompts the user to grant access.
This class keeps track of number of time users deny the permission in order to manipulate the UI/UX to grant the permission.
*/
open class WifiBannerViewModel(
    application: Application
) : AndroidViewModel(application) {
    protected val context get() = getApplication<Application>()
    private val sharedPref = context.getSharedPreferences(NET_DISCDD_BUNDLECLIENT_NUM_DENIED, MODE_PRIVATE)
    private val _numDenied = MutableStateFlow(0)
    val numDenied = _numDenied.asStateFlow()

    init {
        val numDeniedCached = sharedPref.getInt(NET_DISCDD_BUNDLECLIENT_NUM_DENIED, 0)
        _numDenied.value = numDeniedCached
    }

    fun incrementNumDenied() {
        val newNumDenied = _numDenied.value + 1
        _numDenied.value = newNumDenied
        sharedPref.edit().putInt(NET_DISCDD_BUNDLECLIENT_NUM_DENIED, newNumDenied).apply()
    }

    companion object {
        const val NET_DISCDD_BUNDLECLIENT_NUM_DENIED: String = "net.discdd.bundleclient.NUM_DENIED"
    }
}