package net.discdd.bundleclient;

import static java.util.logging.Level.INFO;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.usb.UsbManager;
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
import androidx.fragment.app.Fragment;

import net.discdd.client.bundletransmission.BundleTransmission;
import net.discdd.model.ADU;
import net.discdd.model.BundleDTO;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class UsbFragment extends Fragment {
    private Button usbExchangeButton;
    private Button reloadButton;
    private Button toSettingsButton;
    private TextView toSettingstext;
    private TextView usbConnectionText;
    public static boolean usbConnected = false;
    private StorageManager storageManager;
    private static final Logger logger = Logger.getLogger(UsbFragment.class.getName());
    private static final String usbDirName = "/DDD_transport";
    private ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
    private BundleTransmission bundleTransmission;
    private File usbDirectory;

    public static UsbFragment newInstance() {return new UsbFragment();}

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.usb_fragment, container, false);
        BundleClientActivity activity = (BundleClientActivity) getActivity();
        BundleClientWifiDirectService wifiBgService = WifiServiceManager.INSTANCE.getService();
        if (activity != null && wifiBgService != null) {
            try {
                bundleTransmission = wifiBgService.getBundleTransmission();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error getting bundle transmission: ", e);
            }
        } else {
            logger.log(Level.INFO, "BundleClientActivity or wifiBgService is null");
        }

        reloadButton = view.findViewById(R.id.reload_settings);
        toSettingsButton = view.findViewById(R.id.to_settings_button);
        toSettingstext = view.findViewById(R.id.to_settings_text);
        usbExchangeButton = view.findViewById(R.id.usb_exchange_button);
        usbConnectionText = view.findViewById(R.id.usbconnection_response_text);
        storageManager = (StorageManager) getActivity().getSystemService(Context.STORAGE_SERVICE);

        boolean hasPermission = isManageAllFilesAccessGranted();
        manageAccessGranted(isManageAllFilesAccessGranted());

        if(hasPermission) {
            Toast.makeText(requireContext(), "all files can be accessed", Toast.LENGTH_SHORT).show();
            usbExchangeButton.setOnClickListener(v -> {
                logger.log(Level.INFO, "usbExchangeButton clicked");
                if (usbConnected) {
                    scheduledExecutor.execute(() -> {
                        try {
                            //Bundle we want to send to usb.
                            logger.log(INFO, "Starting bundle creation");
                            BundleDTO bundleDTO = bundleTransmission.generateBundleForTransmission();
                            File bundleFile = bundleDTO.getBundle().getSource();
                            File targetFile = new File(usbDirectory, bundleFile.getName());
                            Files.copy(bundleFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            logger.log(INFO, "Bundle creation and transfer successful");
                            updateUsbStatus(false, "Bundle created and transferred to USB", Color.GREEN);
                        } catch (Exception e) {
                            e.printStackTrace();
                            updateUsbStatus(false, "Error creating or transferring bundle", Color.RED);
                        }
                    });
                } else {
                    Toast.makeText(getActivity(), "No bundle created as usb device not connected", Toast.LENGTH_SHORT)
                            .show();
                }
            });
        }
        else {
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
        BundleClientActivity activity = (BundleClientActivity) getActivity();

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

    private void showUsbAttachedToast() {
        Toast.makeText(getActivity(), getString(R.string.usb_device_attached), Toast.LENGTH_SHORT).show();
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

    private void showUsbDetachedToast() {
        Toast.makeText(getActivity(), getString(R.string.usb_device_detached), Toast.LENGTH_SHORT).show();
    }

    private void processIncomingADU(ADU adu) {
        Intent intent = new Intent("android.intent.dtn.DATA_RECEIVED");
        intent.setPackage(adu.getAppId());
        intent.setType("text/plain");
        getActivity().getApplicationContext().sendBroadcast(intent);
    }
}
