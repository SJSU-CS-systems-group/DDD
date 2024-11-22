package net.discdd.bundletransport;

import static java.util.logging.Level.INFO;

import android.content.Context;
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.discdd.bundlerouting.RoutingExceptions;
import net.discdd.pathutils.TransportPaths;

import org.whispersystems.libsignal.InvalidKeyException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.logging.Level.WARNING;

public class UsbFragment extends Fragment {
    private UsbFileManager usbFileManager;
    private TransportPaths transportPaths;
    private Button usbExchangeButton;
    private TextView usbConnectionText;
    private UsbManager usbManager;
    public static boolean usbConnected = false;
    private StorageManager storageManager;
    private static final Logger logger = Logger.getLogger(UsbFragment.class.getName());
    private ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

    public static UsbFragment newInstance(TransportPaths transportPaths) {
        UsbFragment fragment = new UsbFragment();
        fragment.setTransportPaths(transportPaths);
        return fragment;
    }

    public void setTransportPaths (TransportPaths transportPaths) {
        this.transportPaths = transportPaths;
    }

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.usb_fragment, container, false);

        usbExchangeButton = view.findViewById(R.id.usb_exchange_button);
        usbConnectionText = view.findViewById(R.id.usbconnection_response_text);

        storageManager = (StorageManager) getActivity().getSystemService(Context.STORAGE_SERVICE);
        usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        usbFileManager = new UsbFileManager(storageManager, transportPaths);

        // Check initial USB connection
        checkUsbConnection(3);

        usbExchangeButton.setOnClickListener(v -> {
            logger.log(INFO, "Sync button was hit");
            try {
                usbFileManager.populateUsb();
            } catch (IOException e) {
                logger.log(INFO, "Populate USB was unsuccessful");
                throw new RuntimeException(e);
            }
        });
        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    public boolean checkUsbConnection(int tries) {
        usbConnected = !usbManager.getDeviceList().isEmpty();
        getActivity().getMainExecutor().execute(() -> {
            if (usbConnected) {
                updateUsbStatus(true, getString(R.string.usb_connection_detected), Color.GREEN);
            } else {
                updateUsbStatus(false, getString(R.string.no_usb_connection_detected), Color.RED);
            }
        });
        if (tries > 0 && !usbConnected) {
            scheduledExecutor.schedule(() -> checkUsbConnection(tries - 1), 1, TimeUnit.SECONDS);
        }
        return usbConnected;
    }

    public void updateUsbStatus(boolean isConnected, String statusText, int color) {
        //get parameter info from checkUsbConnection in connection manager
        getActivity().runOnUiThread(() -> {
            usbExchangeButton.setEnabled(isConnected);
            usbConnectionText.setText(statusText);
            usbConnectionText.setTextColor(color);
        });
    }
}