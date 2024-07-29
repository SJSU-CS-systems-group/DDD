package net.discdd.bundleclient;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class PermissionsAdapter extends RecyclerView.Adapter<PermissionsAdapter.PermissionViewHolder> {

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
        int resid = permissionsFragment.getResources()
                .getIdentifier(permission, "string", permissionsFragment.getContext().getPackageName());
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

