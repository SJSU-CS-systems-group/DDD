package net.discdd.bundleclient;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

public class PermissionsFragment extends Fragment{

    private RecyclerView permissionsRecyclerView;
    private PermissionsAdapter permissionsAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.permissions_page, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        permissionsRecyclerView = view.findViewById(R.id.permissions_recycler_view);

        if (permissionsRecyclerView != null) {

            permissionsRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

            //get permissions array from resources
            String[] permissionsArray = getResources().getStringArray(R.array.permissions_array);

            //Initialize the adapter
            permissionsAdapter = new PermissionsAdapter(permissionsArray);

            //set the adapter to the RecyclerView
            permissionsRecyclerView.setAdapter(permissionsAdapter);
        }
    }
}
