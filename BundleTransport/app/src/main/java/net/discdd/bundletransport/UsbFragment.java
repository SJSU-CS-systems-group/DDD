package net.discdd.bundletransport;

import static java.util.logging.Level.INFO;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import net.discdd.pathutils.TransportPaths;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.concurrent.ExecutorService;

public class UsbFragment extends Fragment {
    private UsbFileManager usbFileManager;
    private TransportPaths transportPaths;
    private Button toSettingsButton;
    private Button reloadButton;
    private TextView toSettingstext;
    private Button usbExchangeButton;
    private TextView usbConnectionText;
    public static boolean usbConnected = false;
    private static final String usbDirName = "/DDD_transport";
    private StorageManager storageManager;
    private static final Logger logger = Logger.getLogger(UsbFragment.class.getName());
    private File usbDirectory;

    public static UsbFragment newInstance(TransportPaths transportPaths) {
        UsbFragment fragment = new UsbFragment();
        fragment.setTransportPaths(transportPaths);
        return fragment;
    }

    public void setTransportPaths(TransportPaths transportPaths) {
        this.transportPaths = transportPaths;
    }

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.usb_fragment, container, false);

        reloadButton = view.findViewById(R.id.reload_settings);
        toSettingsButton = view.findViewById(R.id.to_settings_button);
        toSettingstext = view.findViewById(R.id.to_settings_text);
        usbExchangeButton = view.findViewById(R.id.usb_exchange_button);
        usbConnectionText = view.findViewById(R.id.usbconnection_response_text);

        storageManager = (StorageManager) getActivity().getSystemService(Context.STORAGE_SERVICE);
        usbFileManager = new UsbFileManager(storageManager, transportPaths);

        boolean hasPermission = isManageAllFilesAccessGranted();
        manageAccessGranted(isManageAllFilesAccessGranted());

        if (hasPermission) {
            Toast.makeText(requireContext(), "all files can be accessed", Toast.LENGTH_SHORT).show();
            usbExchangeButton.setOnClickListener(v -> {
                logger.log(INFO, "Sync button was hit");
                try {
                    if(usbFileManager.populateUsb()) {
                        updateUsbStatus(false, "Exchange was successful!", Color.GREEN);
                    }
                } catch (IOException e) {
                    logger.log(INFO, "Populate USB was unsuccessful");
                    throw new RuntimeException(e);
                }
            });
        } else {
            Toast.makeText(requireContext(), "no files can be accessed", Toast.LENGTH_SHORT).show();
            toSettingsButton.setOnClickListener(v -> {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
                manageAccessGranted(isManageAllFilesAccessGranted());
            });
            reloadButton.setOnClickListener(v -> {
                if (isManageAllFilesAccessGranted()) {
                    Toast.makeText(requireContext(), "all files can be accessed", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(requireContext(), "no files can be accessed", Toast.LENGTH_SHORT).show();
                }
                manageAccessGranted(isManageAllFilesAccessGranted());
            });
        }

        return view;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        BundleTransportActivity activity = (BundleTransportActivity) getActivity();
        if(activity.usbExists) {
            if(usbDirExists()) {
                updateUsbStatus(true,getString(R.string.usb_connection_detected),Color.GREEN);
            }
            else {
                updateUsbStatus(false,getString(R.string.usb_was_connected_but_ddd_transport_directory_was_not_detected),Color.RED);
            }
        }
        else {
            updateUsbStatus(false, getString(R.string.no_usb_connection_detected), Color.RED);
        }
    }

    private void manageAccessGranted(boolean hasPermission) {
        if (hasPermission) {
            reloadButton.setVisibility(View.GONE);
            toSettingsButton.setVisibility(View.GONE);
            toSettingstext.setVisibility(View.GONE);
            usbExchangeButton.setVisibility(View.VISIBLE);
            usbConnectionText.setVisibility(View.VISIBLE);
        } else {
            usbExchangeButton.setVisibility(View.GONE);
            usbConnectionText.setVisibility(View.GONE);
            reloadButton.setVisibility(View.VISIBLE);
            toSettingsButton.setVisibility(View.VISIBLE);
            toSettingstext.setVisibility(View.VISIBLE);
        }
    }

    private boolean isManageAllFilesAccessGranted() {
        return Environment.isExternalStorageManager();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    //Method to update USB status
    public void updateUsbStatus(boolean isConnected, String statusText, int color) {
        getActivity().runOnUiThread(() -> {
            usbExchangeButton.setEnabled(isConnected);
            usbConnectionText.setText(statusText);
            usbConnectionText.setTextColor(color);
        });
    }
    //Method to check if /DDD_transport directory exists
    private boolean usbDirExists() {
        List<StorageVolume> storageVolumeList = storageManager.getStorageVolumes();
        for (StorageVolume storageVolume : storageVolumeList) {
            if (storageVolume.isRemovable()) {
                usbDirectory = new File(storageVolume.getDirectory().getPath() + usbDirName);
                if (usbDirectory.exists()) {
                    logger.log(INFO, "DDD_transport directory exists.");
                    return true;
                }
            }
        }
        logger.log(INFO,"DDD_transport directory does not exist.");
        return false;
    }
}