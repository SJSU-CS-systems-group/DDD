package net.discdd.bundleclient;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class PermissionsAdapter extends RecyclerView.Adapter<PermissionsAdapter.PermissionViewHolder> {

    private final String[] permissions;

    public PermissionsAdapter(String[] permissions) {
        this.permissions = permissions;
    }

    @NonNull
    @Override
    public PermissionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.permissions_list, parent, false);
        return new PermissionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PermissionViewHolder holder, int position) {
        String permission = permissions[position];
        holder.permissionCaption.setText(permission);
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

