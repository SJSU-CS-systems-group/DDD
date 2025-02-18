package net.discdd.android.fragments;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

public class PermissionsViewModel extends ViewModel {
    private final MutableLiveData<Boolean> _allPermsSatisfied = new MutableLiveData<Boolean>();

    public LiveData<Boolean> getPermissionSatisfied() {
        return _allPermsSatisfied;
    }

    public void updatePermissions(boolean newPermissionSatisfied) {
        _allPermsSatisfied.setValue(newPermissionSatisfied);
    }
}
