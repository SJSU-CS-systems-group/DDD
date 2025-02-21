package net.discdd.bundleclient;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import net.discdd.client.bundletransmission.BundleTransmission;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class BundleManagerFragment extends Fragment {
    private BundleTransmission bundleTransmission;
    private TextView numberBundlesSent;
    private TextView numberBundlesReceived;
    private Button reloadButton;

    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState){
        View view = inflater.inflate(R.layout.bundle_manager_fragment, container, false);
        bundleTransmission = ((BundleClientActivity) getActivity()).wifiBgService.getBundleTransmission();
        numberBundlesSent = view.findViewById(R.id.numberBundlesSent);
        numberBundlesReceived = view.findViewById(R.id.numberBundlesReceived);
        reloadButton = view.findViewById(R.id.reloadCounts);

        reloadButton.setOnClickListener(v -> {
            try {
                numberBundlesSent.setText(getADUcount(bundleTransmission.getClientPaths().sendADUsPath));
                numberBundlesReceived.setText(getADUcount(bundleTransmission.getClientPaths().receiveADUsPath));
            } catch (Exception e) {
                System.err.println("Error updating ADU count: " + e.getMessage());
                numberBundlesSent.setText("Error");
                numberBundlesReceived.setText("Error");
            }
        });

        return view;
    }

    private String getADUcount(Path sendADUsPath) {
        if (sendADUsPath == null) {
            return "0";
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sendADUsPath)) {
            for (Path path : stream) {
                if(path.getFileName().toString().equals("com.fsck.k9.debug")) {
                    // check for "metadata.json" and exclude it
                    if(path.toFile().isDirectory()) {
                        // Get the list of files and exclude "metadata.json"
                        String[] filteredFiles =
                                path.toFile().list((dir, name) -> !name.equals("metadata.json"));
                                return (filteredFiles != null) ? String.valueOf(filteredFiles.length) : "0";
                            }

                        return String.valueOf(path.toFile().list().length);
                }
            }
        }
        catch (IOException | DirectoryIteratorException e) {
            System.err.println("Error reading the directory: " + e.getMessage());
            return "0";
        }
        return "0";
    }

}
