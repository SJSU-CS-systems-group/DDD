package net.discdd.viewmodels;

import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import net.discdd.android_core.R
import java.util.function.Consumer
import java.util.logging.Level
import java.util.logging.Logger

data class PermissionItemData(
    var isBoxChecked: Boolean,
    var permissionName: String
)

class PermissionsViewModel(
    application: Application
) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val logger = Logger.getLogger(PermissionsViewModel::class.java.name)

    private val _allPermsSatisfied = MutableLiveData<Boolean>()
    private val _permissionItems = MutableStateFlow<List<PermissionItemData>>(emptyList())
    val permissionItems: StateFlow<List<PermissionItemData>> = _permissionItems

    private val _neededPermissions = mutableMapOf<String, PermissionItemData>()
    private var permissionWatcher: Consumer<HashSet<String>>? = null
    private var grantedPermissions = HashSet<String>()
    private var requiredPermissions = HashSet<String>()

    init {
        requiredPermissions.addAll(context.resources.getStringArray(R.array.permissions_array).toList())
        _permissionItems.value = requiredPermissions.map { permission ->
            PermissionItemData(
                isBoxChecked = false,
                permissionName = permission
            )
        }
    }

    fun handlePermissionResults(results: Map<String, Boolean>) {
        val remainingPermissions = mutableMapOf<String, PermissionItemData>()
        results.forEach { (p, r) ->
            logger.log(Level.INFO, "$p ${if (r) "granted" else "denied"}")
            updatePermissionItem(p, r)
            if (r) {
                trackGrantedPermission(p)
            } else {
                _neededPermissions[p]?.let {
                    remainingPermissions[p] = it
                }
            }
        }
        _neededPermissions.clear()
        _neededPermissions.putAll(remainingPermissions)
        allSatisfied()
    }

    private fun updatePermissionItem(permission: String, isGranted: Boolean) {
        _permissionItems.update { currentItems ->
            currentItems.map { item ->
                if (item.permissionName == permission) item.copy(isBoxChecked = isGranted)
                 else item
            }
        }
    }

    fun checkPermission(permission: String) {
        logger.log(Level.FINE, "Checking permission $permission")
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            updatePermissionItem(permission, true)
            trackGrantedPermission(permission)
        } else {
            val permissionItem = _permissionItems.value.find { it.permissionName == permission }
            permissionItem?.let {
                updatePermissionItem(permission, false)
                _neededPermissions[permission] = it
            }
        }
    }

    // TODO: instead of getter, expose a public read-only state
    fun getPermissionSatisfied(): LiveData<Boolean> {
        return _allPermsSatisfied
    }

    fun getPermissionsToRequest(): Array<String> {
        return _neededPermissions.keys.toTypedArray()
    }

    fun updatePermissions(newPermissionSatisfied: Boolean) {
        _allPermsSatisfied.value = newPermissionSatisfied
    }

    private fun trackGrantedPermission(permission: String) {
        if (grantedPermissions.contains(permission)) {
            return
        }
        grantedPermissions.add(permission)
        permissionWatcher?.accept(grantedPermissions)
    }

    fun registerPermissionsWatcher(watcher: Consumer<HashSet<String>>) {
        permissionWatcher = watcher
        permissionWatcher!!.accept(grantedPermissions)
    }

    private fun allSatisfied() {
        val satisfied =
            requiredPermissions.isNotEmpty() && grantedPermissions.containsAll(requiredPermissions)
        logger.log(Level.INFO, "ALL PERMS SATISFIED: $satisfied")
        updatePermissions(satisfied)
    }

    fun triggerPermissionDialog(context: Context) {
        AlertDialog.Builder(context)
            .setMessage(R.string.dialog_message)
            .setNeutralButton(R.string.dialog_btn_text, null)
            .create()
            .show()
    }
}