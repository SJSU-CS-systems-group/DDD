package net.discdd.bundletransport;

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

import java.util.concurrent.SubmissionPublisher;

public class StorageFragment extends Fragment {
    //TODO: add loggers
    //private static final Logger logger = Logger.getLogger(ServerUploadFragment.class.getName());
    private SubmissionPublisher<BundleTransportActivity.ConnectivityEvent> connectivityFlow;
    private StorageManager storageManager = new StorageManager(requireActivity().getExternalFilesDir(null).toPath(), last_saved_preference);
    private Button clearStorageBtn;
    private SeekBar seekBar;
    private TextView textView;

    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View mainView = inflater.inflate(R.layout.fragment_server_upload, container, false);
        //seekbar
        //set min?
        seekBar.setMax(getTotalSystemBytes());
        seekBar.setProgress(0);
        textView.setText("Value: " + 100 + " MB");

        // Set up a listener for SeekBar changes
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Calculate the value in MB
                int currentValue = 100 + progress;
                // Update the TextView
                textView.setText("Value: " + currentValue + " MB");
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // Optional: Handle the start of tracking
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Optional: Handle the end of tracking
            }
        });
        //display free space
        //display used space
        //allow to apply preference changes and refresh space specs
        clearStorageBtn = mainView.findViewById(R.id.btn_clear_storage);
        clearStorageBtn.setOnClickListener(view -> {
            //updateStorage();
            Toast.makeText(getContext(), "Cleared outdated storage", Toast.LENGTH_SHORT).show();
        });

        return mainView;
    }

    private int getTotalSystemBytes() {
        StatFs statFs = new StatFs(Environment.getExternalStorageDirectory().getPath());
        int totalBytes = (int) statFs.getTotalBytes();
        return totalBytes;
    }

    private void savePreference() {

    }

    private void saveFree() {

    }
    private void saveUsed() {

    }
}