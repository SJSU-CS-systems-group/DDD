package net.discdd.bundletransport;

import static java.util.logging.Level.INFO;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import java.io.IOException;
import java.util.logging.Logger;

public class StorageFragment extends Fragment {
    private static final Logger logger = Logger.getLogger(StorageFragment.class.getName());
    private static final String PREFS_NAME = "SeekBarPrefs";
    private static final String SEEK_BAR_POSITION = "seekBarPosition";
    private static final int MIN_STORAGE = 100;

    private StorageManager storageManager;
    private Button clearStorageBtn;
    private SeekBar seekBar;
    private TextView seekbarTextView;
    private Toast currentToast;
    private int currentValue;

    public static StorageFragment newInstance() { return new StorageFragment(); }

    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View mainView = inflater.inflate(R.layout.fragment_storage_preferences, container, false);
        storageManager = new StorageManager(requireActivity().getExternalFilesDir(null).toPath(), retrievePreference());

        //link UI elements (seekbar, seekbar text, seek bar max, ) from XML file
        seekBar = mainView.findViewById(R.id.seekBar);
        seekbarTextView = mainView.findViewById(R.id.textView);
        seekBar.setMax(getTotalSystemBytes());
        seekBar.setProgress(retrievePreference());
        seekbarTextView.setText("Value: " + retrievePreference() + " MB");

        // Set up a listener for SeekBar changes
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Calculate the current value in MB
                currentValue = MIN_STORAGE + progress;
                // Update the TextView
                seekbarTextView.setText("Value: " + currentValue + " MB");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                showToast("Changes not saved until you click Set Storage");
            }
        });
        //Display free and used space specs
        TextView textViewFreeSpace = mainView.findViewById(R.id.textViewFreeSpace);
        TextView textViewUsedSpace = mainView.findViewById(R.id.textViewUsedSpace);
        textViewFreeSpace.setText("Free Space: " + getFreeBytes() + " MB");
        textViewUsedSpace.setText("Used Space: " + getUsedBytes() + " MB");
        //Logic to apply preference changes and refresh space specs
        clearStorageBtn = mainView.findViewById(R.id.btn_clear_storage);
        clearStorageBtn.setOnClickListener(view -> {
            try {
                storageManager.setUserStoragePreference(currentValue);
                storageManager.updateStorage();
                textViewFreeSpace.setText("Free Space: " + getFreeBytes() + " MB");
                textViewUsedSpace.setText("Used Space: " + getUsedBytes() + " MB");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            savePreference(seekBar.getProgress());
            showToast("Cleared outdated storage");
        });

        return mainView;
    }

    /**
     * Called to store new preference value.
     * Especially when Set Storage button is clicked.
     *
     * @param position the preference to save
     */
    private void savePreference(int position) {
        SharedPreferences sharedPreferences = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt(SEEK_BAR_POSITION, position);
        editor.apply();
    }

    /**
     * Called to retrieve last preference.
     * Especially when user restarts app.
     *
     * @return lastPreferenceSaved
     */
    private int retrievePreference() {
        SharedPreferences sharedPreferences = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int lastPreferenceSaved = sharedPreferences.getInt(SEEK_BAR_POSITION, 0);
        return lastPreferenceSaved;
    }

    /**
     * Returns all bytes in device storage in MB.
     */
    private int getTotalSystemBytes() {
        StatFs statFs = new StatFs(Environment.getExternalStorageDirectory().getPath());
        int totalBytes = (int) statFs.getTotalBytes();
        int bytesInMB = totalBytes / (1024 * 1024);
        return bytesInMB;
    }

    /**
     * Returns unused bytes in device in MB
     */
    private int getFreeBytes() {
        int total = getTotalSystemBytes();
        int used = getUsedBytes();
        return total - used;
    }

    /**
     * Returns unused bytes in device in MB
     */
    private int getUsedBytes() {
        try {
            var totalFiles = storageManager.getStorageList();
            var sizeOfTotalFiles = storageManager.getStorageSize(totalFiles);
            return (int) sizeOfTotalFiles;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Prevents stacking of toast messages
     */
    private void showToast(String message) {
        if (currentToast != null) {
            currentToast.cancel(); // Cancel the previous toast if it exists
        }
        currentToast = Toast.makeText(getContext(), message, Toast.LENGTH_SHORT); // Create the new toast
        currentToast.show(); // Show the new toast
    }
}