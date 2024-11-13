package net.discdd.bundletransport;

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
import net.discdd.pathutils.TransportPaths;

import org.whispersystems.libsignal.InvalidKeyException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
import static java.nio.file.StandardCopyOption.*;
import static java.util.logging.Level.WARNING;

import static java.nio.file.StandardCopyOption.*;
import static java.util.logging.Level.WARNING;

public class UsbFragment extends Fragment {
    private static final String USB_DIR_NAME = "DDD_transport";
    private static final String RELATIVE_CLIENT_PATH = "client";
    private static final String RELATIVE_SERVER_PATH = "server";
    private TransportPaths transportPaths;
    private Button usbExchangeButton;
    private TextView usbConnectionText;
    private UsbManager usbManager;
    private BroadcastReceiver mUsbReceiver;
    public static boolean usbConnected = false;
    private StorageManager storageManager;
    private static final Logger logger = Logger.getLogger(UsbFragment.class.getName());
    private ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
    private File usbDirectory;

    public UsbFragment(TransportPaths transportPaths) {
        this.transportPaths = transportPaths;
    }

    public UsbFragment(TransportPaths transportPaths) {
        this.transportPaths = transportPaths;
    }

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
        List<StorageVolume> storageVolumes = storageManager.getStorageVolumes();
        for (StorageVolume volume : storageVolumes) {
            if (volume.isRemovable() && !volume.isEmulated()) {
                File usbStorageDir = volume.getDirectory();
                if (usbStorageDir != null) {
                    File usbTransportDir = new File(usbStorageDir, USB_DIR_NAME);
                    File usbTransportToServerDir = new File(usbTransportDir, RELATIVE_SERVER_PATH);
                    File usbTransportToClientDir = new File(usbTransportDir, RELATIVE_CLIENT_PATH);
                    if (!usbTransportDir.exists()) {
                        usbTransportDir.mkdirs();
                        usbTransportToServerDir.mkdirs();
                        usbTransportToClientDir.mkdirs();
                    }
                    try {
                        toClientList(usbTransportToClientDir);
                        toServerList(usbTransportToServerDir);
                    } catch (Exception e) {
                        logger.log(WARNING, "failed to populate USB and or Android device");
                        throw new RuntimeException("Bad call to populate USB or Android device", e);
                    }
                    reduceUsbFiles(usbTransportToServerDir, usbTransportToClientDir);
                }
                break;
            }
        }
    }

    /**
     * Copies for-client files from transport device (handling downstream).
     *
     * @param targetDir target directory; USBs client directory
     * @throws IOException
     */
    private void toClientList(File targetDir) throws IOException, GeneralSecurityException,
            RoutingExceptions.ClientMetaDataFileException, InvalidKeyException {
        List<Path> storageList;
        Path devicePathForClient = transportPaths.toClientPath;
        try (Stream<Path> walk = Files.walk(devicePathForClient)) {
            storageList = walk.filter(Files::isRegularFile).collect(Collectors.toList());
        }
        if (storageList.isEmpty()) {
            logger.log(INFO, "No bundles to download from device to USB");
            logger.log(INFO, "Our empty storageList was " + devicePathForClient);
            return;
        }
        for (Path deviceFilePath : storageList) {
            Path targetPath = targetDir.toPath().resolve(deviceFilePath.getFileName());
            Files.copy(deviceFilePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Copies for-server files from usb (handling upstream)
     *
     * @param sourceDir source directory; USBs server directory
     */
    private void toServerList(File sourceDir) throws IOException {
        List<Path> storageList;
        try (Stream<Path> walk = Files.walk(sourceDir.toPath())) {
            storageList = walk.filter(Files::isRegularFile).collect(Collectors.toList());
        }
        if (storageList.isEmpty()) {
            logger.log(INFO, "No bundles to download from USB to device");
            logger.log(INFO, "Our empty storageList was " + sourceDir);
            return;
        }
        Path devicePathForServer = transportPaths.toServerPath;
        for (Path usbFilePath : storageList) {
            Path targetPath = devicePathForServer.resolve(usbFilePath.getFileName());
            Files.copy(usbFilePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void reduceUsbFiles(File usbTransportToClientDir, File usbTransportToServerDir) throws IOException {
        //inside USB to client
        List<Path> usbToClient;
        try (Stream<Path> walk = Files.walk(usbTransportToClientDir.toPath())) {
            usbToClient = walk.filter(Files::isRegularFile).collect(Collectors.toList());
        }
        for (Path usbFile : usbToClient) {
            //if android to client does not contain such file
            boolean usbFileExistsInAndroid = new File(String.valueOf(transportPaths.toClientPath), usbFile.getFileName().toString()).exists();
            if (!usbFileExistsInAndroid) {
                //delete file from USB
                Files.deleteIfExists(usbFile);
            }
        }
        //inside USB to server
        List<Path> usbToServer;
        try (Stream<Path> walk = Files.walk(usbTransportToServerDir.toPath())) {
            usbToServer = walk.filter(Files::isRegularFile).collect(Collectors.toList());
        }
        for (Path usbFile : usbToServer) {
            //if android contains such a file
            boolean usbFileExistsInAndroid = new File(String.valueOf(transportPaths.toServerPath), usbFile.getFileName().toString()).exists();
            if (!usbFileExistsInAndroid) {
                //delete file from USB
                Files.deleteIfExists(usbFile);
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mUsbReceiver != null) {
            getActivity().unregisterReceiver(mUsbReceiver);
        }
    }

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

    private void checkUsbConnection(int tries) {
        usbConnected = !usbManager.getDeviceList().isEmpty();
        getActivity().getMainExecutor().execute(() -> {
            if (!usbManager.getDeviceList().isEmpty()) {
            } else {
                updateUsbStatus(false, getString(R.string.usb_device_not_connected), Color.RED);
            }
        });
        if (tries > 0 && !usbConnected) {
            scheduledExecutor.schedule(() -> checkUsbConnection(tries - 1), 1, TimeUnit.SECONDS);
        }
    }

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

    private void showUsbDetachedToast() {
        Toast.makeText(getActivity(), getString(R.string.usb_device_detached), Toast.LENGTH_SHORT).show();
    }
}