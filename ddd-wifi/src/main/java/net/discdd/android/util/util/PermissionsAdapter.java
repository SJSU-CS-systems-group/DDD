package net.discdd.android.util.util;

import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import net.discdd.ddd_wifi.R;

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
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.permissions_list, parent, false);
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
            permissionCheckbox = itemView.findViewById(net.discdd.ddd_wifi.R.id.permission_checkbox);
            permissionCaption = itemView.findViewById(net.discdd.ddd_wifi.R.id.permission_caption);
        }
    }
}

