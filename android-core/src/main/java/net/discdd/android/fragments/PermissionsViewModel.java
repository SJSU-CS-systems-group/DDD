package net.discdd.android.fragments;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

public class PermissionsViewModel extends ViewModel {
    private final MutableLiveData<Boolean> permissionSatisfied = new MutableLiveData<Boolean>();

    public LiveData<Boolean> getPermissionSatisfied() {
        return permissionSatisfied;
    }

    public void updatePermissions(boolean newPermissionSatisfied) {
        permissionSatisfied.setValue(newPermissionSatisfied);
    }
}
