package net.discdd.bundleclient;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class PermissionsActivity extends AppCompatActivity {

    private RecyclerView permissionsRecyclerView;
    private PermissionsAdapter permissionsAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.permissions_page);

        permissionsRecyclerView = findViewById(R.id.permissions_recycler_view);
        permissionsRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        String[] permissionsArray = getResources().getStringArray(R.array.permissions_array);

        //debug//
//        for (String permission : permissionsArray) {
//            Log.d("PermissionsActivity", "Permission: " + permission);
//        }
        permissionsAdapter = new PermissionsAdapter(permissionsArray);
        permissionsRecyclerView.setAdapter(permissionsAdapter);
    }
}
