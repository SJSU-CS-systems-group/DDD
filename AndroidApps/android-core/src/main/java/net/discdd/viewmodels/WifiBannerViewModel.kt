package net.discdd.viewmodels

import android.app.Application
import android.content.Context.MODE_PRIVATE
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/*
Viewmodels for screens that require functionality to NEARBY_WIFI_DEVICES will extend this class.
Ensure that the screens include the WifiPermissionBanner which prompts the user to grant access.
This class keeps track of number of time users deny the permission in order to manipulate the UI/UX to grant the permission.
*/
open class WifiBannerViewModel(
        application: Application
) : AndroidViewModel(application) {
    protected val context get() = getApplication<Application>()
    private val sharedPref by lazy {
        context.getSharedPreferences(NET_DISCDD_BUNDLECLIENT_NUM_DENIED, MODE_PRIVATE)
    }
    private val _numDeniedWifi = MutableStateFlow(0)
    val numDeniedWifi = _numDeniedWifi.asStateFlow()
    private val _numDeniedLocation = MutableStateFlow(0)
    val numDeniedLocation = _numDeniedLocation.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _numDeniedWifi.value = sharedPref.getInt(NET_DISCDD_BUNDLECLIENT_NUM_DENIED, 0)
            _numDeniedLocation.value = sharedPref.getInt(NET_DISCDD_BUNDLECLIENT_NUM_DENIED, 0)

        }
    }

    fun incrementNumDeniedWifi() {
        viewModelScope.launch(Dispatchers.IO) {
            val newNumDenied = _numDeniedWifi.value + 1
            _numDeniedWifi.value = newNumDenied
            sharedPref.edit().putInt(NET_DISCDD_BUNDLECLIENT_NUM_DENIED, newNumDenied).apply()
        }
    }

    fun incrementNumDeniedLocation() {
        viewModelScope.launch(Dispatchers.IO) {
            val newNumDenied = _numDeniedLocation.value + 1
            _numDeniedLocation.value = newNumDenied
            sharedPref.edit().putInt(NET_DISCDD_BUNDLECLIENT_NUM_DENIED, newNumDenied).apply()
        }
    }

    companion object {
        const val NET_DISCDD_BUNDLECLIENT_NUM_DENIED: String = "net.discdd.bundleclient.NUM_DENIED"
    }
}