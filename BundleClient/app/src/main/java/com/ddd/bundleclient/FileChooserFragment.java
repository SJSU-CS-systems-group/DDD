package com.ddd.bundleclient;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link FileChooserFragment #newInstance} factory method to
 * create an instance of this fragment.
 */
public class FileChooserFragment extends Fragment {

    private static final int MY_REQUEST_CODE_PERMISSION = 1000;
    private static final int MY_RESULT_CODE_FILECHOOSER = 2000;
    private static final String LOG_TAG = "AndroidExample";
    //Venus added
    private static final Logger logger = Logger.getLogger(FileChooserFragment.class.getName());

    private Button buttonBrowse;
    private EditText editTextPath;

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
                             final Bundle savedInstanceState) {

        View rootView = inflater.inflate(R.layout.fragment_file_chooser, container, false);

        this.editTextPath = rootView.findViewById(R.id.editText_path);
        this.buttonBrowse = rootView.findViewById(R.id.button_browse);

        this.buttonBrowse.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View view) {
                askPermissionAndBrowseFile();
            }

        });
        return rootView;
    }

    private void askPermissionAndBrowseFile() {
        // With Android Level >= 23, you have to ask the user
        // for permission to access External Storage.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) { // Level 23

            // Check if we have Call permission
            int permisson =
                    ActivityCompat.checkSelfPermission(this.getContext(), Manifest.permission.READ_EXTERNAL_STORAGE);

            if (permisson != PackageManager.PERMISSION_GRANTED) {
                // If don't have permission so prompt the user.
                this.requestPermissions(new String[] { Manifest.permission.READ_EXTERNAL_STORAGE },
                                        MY_REQUEST_CODE_PERMISSION);
                return;
            }
        }
        this.doBrowseFile();
    }

    private void doBrowseFile() {
        Intent chooseFileIntent = new Intent(Intent.ACTION_GET_CONTENT);
        chooseFileIntent.setType("*/*");
        // Only return URIs that can be opened with ContentResolver
        chooseFileIntent.addCategory(Intent.CATEGORY_OPENABLE);

        chooseFileIntent = Intent.createChooser(chooseFileIntent, "Choose a file");
        startActivityForResult(chooseFileIntent, MY_RESULT_CODE_FILECHOOSER);
    }

    // When you have the request results
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        //
        switch (requestCode) {
            case MY_REQUEST_CODE_PERMISSION: {

                // Note: If request is cancelled, the result arrays are empty.
                // Permissions granted (CALL_PHONE).
//  //              if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
//
//                    Log.i(LOG_TAG, "Permission granted!");
//                    Toast.makeText(this.getContext(), "Permission granted!", Toast.LENGTH_SHORT).show();
//
//                    this.doBrowseFile();
//                }

                //Venus added
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    logger.log(Level.INFO, "Permission Granted!");
                    Toast.makeText(this.getContext(), "Permission Granted!", Toast.LENGTH_SHORT).show();
                    this.doBrowseFile();
                } else {
                    logger.log(Level.WARNING, "Permission Denied!");
                    Toast.makeText(this.getContext(), "Permission Denied!", Toast.LENGTH_SHORT).show();

                }
                break;

//    //            // Cancelled or denied.
//                else {
//                    Log.i(LOG_TAG, "Permission denied!");
//                    Toast.makeText(this.getContext(), "Permission denied!", Toast.LENGTH_SHORT).show();
//                }
//                break;
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case MY_RESULT_CODE_FILECHOOSER:
                if (resultCode == Activity.RESULT_OK) {
                    if (data != null) {
                        Uri fileUri = data.getData();
                        Log.i(LOG_TAG, "Uri: " + fileUri);

                        String filePath = null;
                        try {
                            filePath = FileUtils.getPath(this.getContext(), fileUri);
                        } catch (Exception e) {
                            Log.e(LOG_TAG, "Error: " + e);
                            Toast.makeText(this.getContext(), "Error: " + e, Toast.LENGTH_SHORT).show();
                        }
                        this.editTextPath.setText(filePath);
                    }
                }
                break;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    public String getPath() {
        return this.editTextPath.getText().toString();
    }
}