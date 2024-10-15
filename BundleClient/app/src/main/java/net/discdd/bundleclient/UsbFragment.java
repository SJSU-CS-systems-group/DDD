package net.discdd.bundleclient;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
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

import net.discdd.client.bundletransmission.BundleTransmission;
import net.discdd.model.ADU;
import net.discdd.model.BundleDTO;

import java.io.File;
import java.io.IOException;
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
    private TextView usbConnectionText;
    private UsbManager usbManager;
    private BroadcastReceiver mUsbReceiver;
    public static boolean usbConnected = false;
    private StorageManager storageManager;
    private static final Logger logger = Logger.getLogger(UsbFragment.class.getName());
    private static final String usbDirName = "/DDD_transport";
    private ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
    private BundleTransmission bundleTransmission;
    private File usbDirectory;

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.usb_fragment, container, false);

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
        checkUsbConnection(2);

        usbExchangeButton.setOnClickListener(v -> {
            logger.log(Level.INFO, "usbExchangeButton clicked");
            if(usbConnected) {
                //Bundle we want to send to usb.
                scheduledExecutor.execute(() -> {
                    try {
                        bundleCreation();
                        logger.log(Level.INFO, "Starting bundle creation");
                        BundleDTO bundleDTO = bundleTransmission.generateBundleForTransmission();
                        logger.log(Level.INFO, "Bundletransmission created successfully");
                        File bundleFile = bundleDTO.getBundle().getSource();
                        updateUsbStatus(false, "Checkpoint 3", Color.GREEN);
                        File targetFile = new File(usbDirectory, bundleFile.getName());
                        updateUsbStatus(false, "file checkpoint successful", Color.GREEN);
                        Files.copy(bundleFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        updateUsbStatus(false, "Bundle created and transferred to USB", Color.GREEN);
                    } catch (Exception e) {
                        e.printStackTrace();
                        updateUsbStatus(false, "Error creating or transferring bundle", Color.RED);
                    }
                });
            } else {
                Toast.makeText(getActivity(), "No bundle created as usb device not connected", Toast.LENGTH_SHORT).show();
            }
        });

        return view;
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
            if (storageVolume.isRemovable()) {
                usbDirectory = new File(storageVolume.getDirectory().getPath() + usbDirName);
                if (usbDirectory.exists()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void showUsbDetachedToast() {
        Toast.makeText(getActivity(), getString(R.string.usb_device_detached), Toast.LENGTH_SHORT).show();
    }

    private void bundleCreation () {
        try {
            bundleTransmission = new BundleTransmission(usbDirectory.toPath(), this::processIncomingADU);
        } catch (Exception e) {
            e.printStackTrace();
            updateUsbStatus(false, "Error creating or transferring bundle in bundleCreation", Color.RED);
        }
    }

    private void processIncomingADU(ADU adu) {
        //notify app that someone sent data for the app
        Intent intent = new Intent("android.intent.dtn.DATA_RECEIVED");
        intent.setPackage(adu.getAppId());
        intent.setType("text/plain");
        getActivity().getApplicationContext().sendBroadcast(intent);
    }
}
