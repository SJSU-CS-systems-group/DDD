package net.discdd.viewmodels;

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.discdd.android_core.R
import java.util.function.Consumer
import java.util.logging.Level
import java.util.logging.Logger

data class PermissionItemData(
    var isBoxChecked: Boolean,
    var permissionName: String
)

class PermissionsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val _permissionItems = MutableStateFlow<List<PermissionItemData>>(emptyList())
    val permissionItems: StateFlow<List<PermissionItemData>> = _permissionItems

    private val _neededPermissions = mutableMapOf<String, PermissionItemData>()
    private var requiredPermissions = context.resources.getStringArray(R.array.permissions_array).toList()

    init {
        _permissionItems.value = requiredPermissions.map { permission ->
            PermissionItemData(
                isBoxChecked = false,
                permissionName = permission
            )
        }
    }

    @OptIn(ExperimentalPermissionsApi::class)
    fun addRuntimePerms(runtimePermissions: List<PermissionState>) {
        _permissionItems.update { currentItems ->
            val currentPermissions = currentItems.map { it.permissionName }

            val newItems = runtimePermissions
                .filter { permState -> permState.permission !in currentPermissions }
                .map { permState ->
                    PermissionItemData(
                        isBoxChecked = permState.status.isGranted,
                        permissionName = permState.permission
                    )
                }

            currentItems + newItems
        }
    }

    private fun updatePermissionItem(permission: String, isGranted: Boolean) {
        _permissionItems.update { currentItems ->
            currentItems.map { item ->
                if (item.permissionName == permission) item.copy(isBoxChecked = isGranted)
                else item
            }
        }
    }

    fun checkPermission(permission: String, activity: Activity) {
        viewModelScope.launch {
            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                updatePermissionItem(permission, true)
            } else {
                val permissionItem = _permissionItems.value.find { it.permissionName == permission }
                permissionItem?.let {
                    updatePermissionItem(permission, false)
                    _neededPermissions[permission] = it
                }
            }
        }
    }
}
