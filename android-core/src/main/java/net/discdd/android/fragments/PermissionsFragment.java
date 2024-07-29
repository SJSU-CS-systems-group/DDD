package net.discdd.android.fragments;

import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.discdd.android_core.R;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PermissionsFragment extends Fragment {
    final static private Logger logger = Logger.getLogger(PermissionsFragment.class.getName());
    private final HashMap<String, PermissionsAdapter.PermissionViewHolder> neededPermissions = new HashMap<>();
    private ActivityResultLauncher<String> requestPermissionLauncher;
    private PermissionsAdapter permissionsAdapter;
    private boolean promptPending;
    private Consumer<HashSet<String>> permissionWatcher;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.permissions_fragment, container, false);
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

    private ActivityResultLauncher<String[]> activityResultLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), new ActivityResultCallback<Map<String, Boolean>>() {
        @Override
        public void onActivityResult(Map<String, Boolean> results) {
            // go through the permissions and result pairs
            results.forEach((p, r) -> {
                logger.log(Level.INFO, p + " " + (r ? "granted" : "denied"));
                var view = neededPermissions.remove(p);
                if (view != null) {
                    view.permissionCheckbox.setChecked(r);
                }
            });
            if (neededPermissions.isEmpty()) {
                promptPending = false;
            } else {
                String[] permissionsToRequest = neededPermissions.keySet().toArray(new String[0]);
                logger.info("Requesting " + Arrays.toString(permissionsToRequest));
                activityResultLauncher.launch(permissionsToRequest);
            }
        }
    });;

    // TODO: in the future, we should enable removing from this set
    HashSet<String> grantedPermissions = new HashSet<>();

    /*
     * The Main activity uses this to call us back with the results of permission requests
     */
    public void processPermissionResults(String[] permissions, int[] grantResults) {
        logger.log(Level.INFO, "Permission request results:");
        for (var i = 0; i < permissions.length; i++) {
            boolean permissionGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
            logger.log(Level.INFO, permissions[i] + " " + (permissionGranted ? "granted" : "denied"));
            if (permissionGranted) {
                trackGrantedPermission(permissions[i]);
            }
            var view = neededPermissions.remove(permissions[i]);
            if (view != null) {
                view.permissionCheckbox.setChecked(permissionGranted);
            }
        }
        if (neededPermissions.isEmpty()) {
            promptPending = false;
        } else {
            String[] permissionsToRequest = neededPermissions.keySet().toArray(new String[0]);
            logger.info("Requesting " + Arrays.toString(permissionsToRequest));
            activityResultLauncher.launch(permissionsToRequest);
        }
    }

    private void trackGrantedPermission(String permissions) {
        if (grantedPermissions.contains(permissions)) {
            return;
        }
        grantedPermissions.add(permissions);
        if (permissionWatcher != null) {
            permissionWatcher.accept(grantedPermissions);
        }
    }

    public void registerPermissionsWatcher(Consumer<HashSet<String>> watcher) {
        permissionWatcher = watcher;
        permissionWatcher.accept(grantedPermissions);
    }

    public void checkPermission(String permission, PermissionsAdapter.PermissionViewHolder holder) {
        if (ContextCompat.checkSelfPermission(getContext(), permission) == PackageManager.PERMISSION_GRANTED) {
            holder.permissionCheckbox.setChecked(true);
            trackGrantedPermission(permission);
        } else {
            neededPermissions.put(permission, holder);
            holder.permissionCheckbox.setChecked(false);
            if (!promptPending) {
                promptPending = true;
                String[] permissionsToRequest = neededPermissions.keySet().toArray(new String[0]);
                logger.info("Requesting " + Arrays.toString(permissionsToRequest));
                activityResultLauncher.launch(permissionsToRequest);
            }
        }
    }

    static class PermissionsAdapter extends RecyclerView.Adapter<PermissionsAdapter.PermissionViewHolder> {

        final String[] permissions;
        private final PermissionsFragment permissionsFragment;

        public PermissionsAdapter(PermissionsFragment permissionsFragment, String[] permissions) {
            this.permissions = permissions;
            this.permissionsFragment = permissionsFragment;
        }

        @NonNull
        @Override
        public PermissionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.permissions_listitem, parent, false);
            var viewHolder = new PermissionViewHolder(view);
            return viewHolder;
        }

        @Override
        public void onBindViewHolder(@NonNull PermissionViewHolder holder, int position) {
            String permission = permissions[position];
            // we are going to use the string translations keyed on the permission name to get
            // the description of the permission
            Resources resources = permissionsFragment.getResources();
            int resid = resources.getIdentifier(permission, "string", "net.discdd.ddd_wifi");
            holder.permissionCaption.setText(resid == 0 ? permission : permissionsFragment.getString(resid));
            permissionsFragment.checkPermission(permission, holder);
        }

        @Override
        public int getItemCount() {
            return permissions.length;
        }

        public static class PermissionViewHolder extends RecyclerView.ViewHolder {

            CheckBox permissionCheckbox;
            TextView permissionCaption;

            public PermissionViewHolder(@NonNull View itemView) {
                super(itemView);
                permissionCheckbox = itemView.findViewById(R.id.permission_checkbox);
                permissionCaption = itemView.findViewById(R.id.permission_caption);
            }
        }
    }
}
