package net.discdd.viewmodels;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

class PermissionsViewModel : ViewModel() {
    private val _allPermsSatisfied = MutableLiveData<Boolean>()

    fun updatePermissions(newPermissionSatisfied: Boolean) {
        _allPermsSatisfied.value = newPermissionSatisfied
    }

    // TODO: instead of getter, expose a public read-only state
    fun getPermissionSatisfied(): LiveData<Boolean> {
        return _allPermsSatisfied
    }
}