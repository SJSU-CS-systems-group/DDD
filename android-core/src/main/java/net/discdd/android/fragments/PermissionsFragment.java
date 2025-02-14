package net.discdd.android.fragments;

import android.content.pm.PackageManager;
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
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.discdd.android_core.R;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PermissionsFragment extends Fragment {
    final static private Logger logger = Logger.getLogger(PermissionsFragment.class.getName());
    private final HashMap<String, PermissionsAdapter.PermissionViewHolder> neededPermissions = new HashMap<>();
    private ActivityResultLauncher<String> requestPermissionLauncher;
    private boolean promptPending;
    private Consumer<HashSet<String>> permissionWatcher;
    private PermissionsViewModel permissionsViewModel;
    private final HashSet<String> requiredPermissions = new HashSet<>();

    public static PermissionsFragment newInstance() {
        return new PermissionsFragment();
    }

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        permissionsViewModel = new ViewModelProvider(requireActivity()).get(PermissionsViewModel.class);
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

            //Store required permissions
            requiredPermissions.addAll(Arrays.asList(permissions));

            //Initialize the adapter
            PermissionsAdapter permissionsAdapter = new PermissionsAdapter(permissions);
            permissionsAdapter.setOnClickListener(v -> {
                triggerPermission();
            });

            //set the adapter to the RecyclerView
            permissionsRecyclerView.setAdapter(permissionsAdapter);
        }
    }

    public void allSatisfied() {
        boolean satisfied = !requiredPermissions.isEmpty() && grantedPermissions.containsAll(requiredPermissions);
        logger.log(Level.INFO, "ALL PERMS SATISFIED: " + satisfied);
        permissionsViewModel.updatePermissions(satisfied);
    }

    private ActivityResultLauncher<String[]> activityResultLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                                      new ActivityResultCallback<Map<String, Boolean>>() {
                                          @Override
                                          public void onActivityResult(Map<String, Boolean> results) {
                                              // go through the permissions and result pairs
                                              AtomicInteger unresolvedPermissionCount = new AtomicInteger();
                                              results.forEach((p, r) -> {
                                                  logger.log(Level.INFO, p + " " + (r ? "granted" : "denied"));
                                                  var view = neededPermissions.remove(p);
                                                  if (view != null) {
                                                      view.permissionCheckbox.setChecked(r);
                                                  }
                                                  if (r) {
                                                      trackGrantedPermission(p);
                                                  } else {
                                                      unresolvedPermissionCount.getAndIncrement();
                                                  }
                                              });
                                              if (neededPermissions.isEmpty()) {
                                                  promptPending = false;
                                              } else if (unresolvedPermissionCount.get() > 0) {
                                                  String[] permissionsToRequest =
                                                          neededPermissions.keySet().toArray(new String[0]);
                                                  logger.info("Requesting " + Arrays.toString(permissionsToRequest));
                                                  activityResultLauncher.launch(permissionsToRequest);
                                              }
                                              allSatisfied();
                                          }
                                      });
    ;

    private void triggerPermission() {
        PermissionsDialogFragment dialog = new PermissionsDialogFragment();
        dialog.show(getParentFragmentManager(), "permission");
    }

    // TODO: in the future, we should enable removing from this set
    HashSet<String> grantedPermissions = new HashSet<>();

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
        logger.info("Checking permission " + permission);
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

    class PermissionsAdapter extends RecyclerView.Adapter<PermissionsAdapter.PermissionViewHolder> {
        private OnClickListener listener;
        final String[] permissions;
        public PermissionsAdapter(String[] permissions) {
            this.permissions = permissions;
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

            int resid = getResources().getIdentifier(permission, "string", getActivity().getPackageName());
            holder.permissionCaption.setText(resid == 0 ? permission : getString(resid));
            checkPermission(permission, holder);
            allSatisfied();

            holder.itemView.setOnClickListener(v -> {
                if (listener != null && !holder.permissionCheckbox.isChecked()) {
                    listener.onClick(v);
                }
            });
        }

        @Override
        public int getItemCount() {
            return permissions.length;
        }

        public void setOnClickListener(OnClickListener listener) {
            this.listener = listener;
        }

        public interface OnClickListener {
            void onClick(View v);
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
