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

import net.discdd.bundlerouting.RoutingExceptions;
import net.discdd.client.bundletransmission.BundleTransmission;
import net.discdd.model.BundleDTO;

import org.whispersystems.libsignal.InvalidKeyException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class UsbFragment extends Fragment {
    private Button usbExchangeButton;
    private TextView usbConnectionText;
    private UsbManager usbManager;
    private BroadcastReceiver mUsbReceiver;
    public static boolean usbConnected = false;
    private StorageManager storageManager;
    private static final Logger logger = Logger.getLogger(UsbFragment.class.getName());
    private static final String usbDirName = "/DDD_transport";
    private Path usbDirPath;
    private ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
    private File usbDirectory;
    private BundleTransmission bundleTransmission;

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.usb_fragment, container, false);
        bundleTransmission = ((BundleTransportActivity) getActivity()).btService.getBundleTransmission();

        usbExchangeButton = view.findViewById(R.id.usb_exchange_button);
        usbConnectionText = view.findViewById(R.id.usbconnection_response_text);

        usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        mUsbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleUsbBroadcast(intent);
            }
        };
        storageManager = (StorageManager) getActivity().getSystemService(Context.STORAGE_SERVICE);

        //Register USB broadcast receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        getActivity().registerReceiver(mUsbReceiver, filter);

        // Check initial USB connection
        checkUsbConnection(3);

        usbExchangeButton.setOnClickListener(v -> {
            try {
                populateUsb();
            } catch (IOException e) {
                logger.log(INFO, "Populate USB was unsuccessful");
                throw new RuntimeException(e);
            }
            logger.log(INFO, "Sync button was hit");
        });
        return view;
    }

    /**
     * Finds appropriate usb dir for ddd activities and copies device files.
     *
     * @throws IOException
     */
    private void populateUsb() throws IOException {
//        //check if ddd dir exists and write bundles over
//        //else create ddd dir where it should be and write bundles over
//        List<StorageVolume> storageVolumes = storageManager.getStorageVolumes();
//        for (StorageVolume volume : storageVolumes) {
//            // Check that volume is not internal storage, SSD, or hard drive
//            // ie. non-removable storage is internal storage, SSD, and hard drives
//            if (volume.isRemovable()) {
//                // Get the root directory for the USB storage
//                File usbStorageDir = volume.getDirectory();
//                // If the root dir exists
//                if (usbStorageDir != null) {
//                    // Create the new directory under the USB storage
//                    File dddTransportDir = new File(usbStorageDir, "DDD_transport");
//                    // Check if the DDD_transport directory exists
//                    if (!dddTransportDir.exists()) {
//                        // Directory does not exist, so create it
//                        dddTransportDir = new File(usbStorageDir, "/DDD_transport/BundleTransmission/server");
//                        dddTransportDir.mkdirs();
//                        dddTransportDir = new File(usbStorageDir, "DDD_transport/BundleTransmission/client");
//                        dddTransportDir.mkdirs();
//                    }
//                    // after making proper directories, copy for-client files from transport
//                    copyClientFilesFromDevice(dddTransportDir);
//                }
//                break; //once first removable volume is found exit for loop (handles USBs with multiple partitions/volumes)
////            }
//        }
        File dddTransportDir = new File(String.valueOf(usbDirPath), "DDD_transport");
        logger.log(INFO, "Made the following file for directories creation: " + dddTransportDir);
        dddTransportDir = new File(String.valueOf(usbDirPath), "/DDD_transport/BundleTransmission/server");
        dddTransportDir.mkdirs();
        logger.log(INFO, "Made the following directory: " + dddTransportDir);
        dddTransportDir = new File(String.valueOf(usbDirPath), "/DDD_transport/BundleTransmission/client");
        dddTransportDir.mkdirs();
        logger.log(INFO, "Made the following directory: " + dddTransportDir);
//    }
    // after making proper directories, copy for-client files from transport
        logger.log(INFO, "copying client files from device");
        try {
            copyClientFilesFromDevice(dddTransportDir);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        logger.log(INFO, "copied client files from device");
    }

    /**
     * Copies for-client files from transport device (handling downstream).
     *
     * @param dddTransportDir target directory; USBs directory
     * @throws IOException
     */
    private void copyClientFilesFromDevice(File dddTransportDir) throws IOException, GeneralSecurityException, RoutingExceptions.ClientMetaDataFileException, InvalidKeyException {
        //for every client bundle in device, copy onto usb
        //how will client know which bundle is THEIRS (does bundle or file-path hv client ID?)
//        List<Path> storageList;
//        String s = requireActivity().getExternalFilesDir(null) + "/BundleTransmission/client";
//        Path devicePathForClient = Paths.get(s);
//        try (Stream<Path> walk = Files.walk(devicePathForClient)) {
//            storageList = walk.filter(Files::isRegularFile).collect(Collectors.toList());
//        }
//        if (storageList.isEmpty()) {
//            //TODO: inform no bundles to download onto USB
//            logger.log(INFO, "No bundles to download from device to USB");
//            return;
//        }
//        //for every client file in transport, copy onto USBs designated dir
//        for (Path deviceFilePath : storageList) {
//            logger.log(INFO, "copying" + deviceFilePath + "onto " + dddTransportDir.toPath());
//            Files.copy(deviceFilePath, dddTransportDir.toPath(), StandardCopyOption.REPLACE_EXISTING);
//        }
        BundleDTO bundleDTO = bundleTransmission.generateBundleForTransmission();
        File bundleFile = bundleDTO.getBundle().getSource();
        File targetFile = new File(usbDirectory, bundleFile.getName());
        Files.copy(bundleFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
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
            usbConnected = false;
            showUsbDetachedToast();
        } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            updateUsbStatus(false, getString(R.string.usb_device_attached_checking_for_storage_volumes), Color.BLUE);
            scheduledExecutor.schedule(() -> checkUsbConnection(2), 1, TimeUnit.SECONDS);
            showUsbAttachedToast();
        }
    }

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
            logger.info("STORAGE " + storageVolume.getDirectory().getPath());
            if (storageVolume.isRemovable()) {
                usbDirectory = new File(storageVolume.getDirectory().getPath() + usbDirName);
                logger.info("CHECKING FOR  " + usbDirectory);
                if (usbDirectory.exists()) {
                    usbDirPath = usbDirectory.toPath();
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