package net.discdd.bundletransport;

import static java.util.logging.Level.INFO;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class UsbFragment extends Fragment {
    private static final Logger logger = Logger.getLogger(StorageManager.class.getName());
    private TextView usbConnectionText;
    private static final String usbDirName = "/DDD_transport";
    private UsbManager usbManager;
    private BroadcastReceiver mUsbReceiver;
    private StorageManager storageManager;
    public static boolean usbConnected = false;
    private ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
    private Button usbExchangeButton;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable
    Bundle savedInstanceState) {
        View mainView = inflater.inflate(R.layout.usb_fragment, container, false);
        usbConnectionText = mainView.findViewById(R.id.usbconnection_response_text);
        usbExchangeButton = mainView.findViewById(R.id.usb_exchange_button);
        //initialize usbManager such that we can check device attachment/ detachment later
        usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        //initialize storageManager such that we can check storage volumes later
        storageManager = (StorageManager) getActivity().getSystemService(Context.STORAGE_SERVICE);
        //FIX?
        //catches usb related broadcasts
        mUsbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleUsbBroadcast(intent);
            }
        };
        //Register USB broadcast receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        getActivity().registerReceiver(mUsbReceiver, filter);
        // Check initial USB connection
        checkUsbConnection(1);

        usbExchangeButton.setOnClickListener(view -> {
            try {
                populateUsb();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            logger.log(INFO, "Sync button was hit");
        });
        return mainView;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mUsbReceiver != null) {
            getActivity().unregisterReceiver(mUsbReceiver);
        }
    }

    //Method to handle USB broadcasts
    private void handleUsbBroadcast(Intent intent) {
        String action = intent.getAction();
        if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
            updateUsbStatus(false, getString(R.string.no_usb_connection_detected), Color.RED);
            UsbFragment.usbConnected = false;
            showUsbDetachedToast();
        } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            updateUsbStatus(false, getString(R.string.usb_device_attached_checking_for_storage_volumes), Color.BLUE);
            scheduledExecutor.schedule(() -> checkUsbConnection(1), 1, TimeUnit.SECONDS);
            showUsbAttachedToast();
            try {
                populateUsb();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Finds appropriate usb dir for ddd activities and copies device files.
     *
     * @throws IOException
     */
    private void populateUsb() throws IOException {
        //check if ddd dir exists and write bundles over
        //else create ddd dir where it should be and write bundles over
        List<StorageVolume> storageVolumes = storageManager.getStorageVolumes();
        for (StorageVolume volume : storageVolumes) {
            // Check that volume is not internal storage, SSD, or hard drive
            // ie. non-removable storage is internal storage, SSD, and hard drives
            if (volume.isRemovable()) {
                // Get the root directory for the USB storage
                File usbStorageDir = volume.getDirectory();
                // If the root dir exists
                if (usbStorageDir != null) {
                    // Create the new directory under the USB storage
                    File dddTransportDir = new File(usbStorageDir, "DDD_transport");
                    // Check if the DDD_transport directory exists
                    if (!dddTransportDir.exists()) {
                        // Directory does not exist, so create it
                        dddTransportDir = new File(usbStorageDir, "DDD_transport/BundleTransmission/for_server");
                        dddTransportDir.mkdirs();
                        dddTransportDir = new File(usbStorageDir, "DDD_transport/BundleTransmission/for_client");
                        dddTransportDir.mkdirs();
                    }
                    // after making proper directories, copy for-client files from transport
                    copyClientFilesFromDevice(dddTransportDir);
                }
                break; //once first removable volume is found exit for loop (handles USBs with multiple partitions/volumes)
            }
        }
    }

    /**
     * Copies for-client files from transport device (handling downstream).
     *
     * @param dddTransportDir target directory; USBs directory
     * @throws IOException
     */
    private void copyClientFilesFromDevice(File dddTransportDir) throws IOException {
        //for every client bundle in device, copy onto usb
        //how will client know which bundle is THEIRS (does bundle or file-path hv client ID?)
        List<Path> storageList;
        String s = requireActivity().getExternalFilesDir(null) + "/client";
        Path devicePathForClient = Paths.get(s);
        try (Stream<Path> walk = Files.walk(devicePathForClient)) {
            storageList = walk.filter(Files::isRegularFile).collect(Collectors.toList());
        }
        if (storageList.isEmpty()) {
            //TODO: inform no bundles to download onto USB
            logger.log(INFO, "No bundles to download from device to USB");
            return;
        }
        //for every client file in transport, copy onto USBs designated dir
        for (Path deviceFilePath : storageList) {
            Files.copy(deviceFilePath, dddTransportDir.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    //TODO delete and replace with an improved broadcast receiver
    //Method to check initial USB connection
    //Method to check initial USB connection
    private void checkUsbConnection(int tries) {
        usbConnected = !usbManager.getDeviceList().isEmpty() && usbDirExists();
        getActivity().getMainExecutor().execute(() -> {
            if (!usbManager.getDeviceList().isEmpty()) {
                if (usbDirExists()) {
                    updateUsbStatus(true, getString(R.string.usb_connection_detected), Color.GREEN);
                } else {
                    updateUsbStatus(false,
                                    getString(R.string.usb_was_connected_but_ddd_transport_directory_was_not_detected),
                                    Color.RED);
                }
            } else {
                updateUsbStatus(false, getString(R.string.usb_device_not_connected), Color.RED);
            }
        });
        if (tries > 0 && !usbConnected) {
            scheduledExecutor.schedule(() -> checkUsbConnection(tries - 1), 1, TimeUnit.SECONDS);
        }
    }

    //Method to update USB status
    public void updateUsbStatus(boolean isConnected, String statusText, int color) {
        usbExchangeButton.setEnabled(isConnected);
        usbConnectionText.setText(statusText);
        usbConnectionText.setTextColor(color);
    }

    private void showUsbAttachedToast() {
        Toast.makeText(getActivity(), getString(R.string.usb_device_attached), Toast.LENGTH_SHORT).show();
    }

    //Method to check if /DDD_transport directory exists
    private boolean usbDirExists() {
        List<StorageVolume> storageVolumeList = storageManager.getStorageVolumes();
        for (StorageVolume storageVolume : storageVolumeList) {
            if (storageVolume.isRemovable()) {
                File fileUsb = new File(storageVolume.getDirectory().getPath() + usbDirName);
                if (fileUsb.exists()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void showUsbDetachedToast() {
        Toast.makeText(getActivity(), getString(R.string.usb_device_detached), Toast.LENGTH_SHORT).show();
    }
}
