package net.discdd.bundleclient;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PermissionsFragment extends Fragment {
    final static private Logger logger = Logger.getLogger(PermissionsFragment.class.getName());
    private final HashMap<String, PermissionsAdapter.PermissionViewHolder> neededPermissions =
            new HashMap<>();
    private ActivityResultLauncher<String> requestPermissionLauncher;
    private PermissionsAdapter permissionsAdapter;
    private boolean promptPending;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.permissions_page, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        RecyclerView permissionsRecyclerView = view.findViewById(R.id.permissions_recycler_view);
        if (permissionsRecyclerView != null) {
            permissionsRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

            //get permissions array from resources
            String[] permissions = getResources().getStringArray(R.array.permissions_array);

            //Initialize the adapter
            permissionsAdapter = new PermissionsAdapter(this, permissions);

            //set the adapter to the RecyclerView
            permissionsRecyclerView.setAdapter(permissionsAdapter);

        }
    }

    /**
     * The Main activity uses this to call us back with the results of permission requests
     */
    public void processPermissionResults(String[] permissions, int[] grantResults) {
        logger.log(Level.INFO, "Permission request results:");
        for (var i = 0; i < permissions.length; i++) {
            logger.log(Level.INFO, permissions[i] + " " +
                    (grantResults[i] == PackageManager.PERMISSION_GRANTED ? "granted" : "denied"));
            var view = neededPermissions.remove(permissions[i]);
            if (view != null) {
                view.permissionCheckbox.setChecked(
                        grantResults[i] == PackageManager.PERMISSION_GRANTED);
            }
        }
        if (neededPermissions.isEmpty()) {
            promptPending = false;
        } else {
            getActivity().requestPermissions(neededPermissions.keySet().toArray(new String[0]),
                                             8675309);
        }
    }

    public void checkPermission(String permission, PermissionsAdapter.PermissionViewHolder holder) {
        if (getActivity().checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            holder.permissionCheckbox.setChecked(true);
        } else {
            neededPermissions.put(permission, holder);
            holder.permissionCheckbox.setChecked(false);
            if (!promptPending) {
                promptPending = true;
                getActivity().requestPermissions(neededPermissions.keySet().toArray(new String[0]),
                                                 8675309);
            }
        }
    }
}
