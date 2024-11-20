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
    private final TransportPaths transportPaths;
    private Button toSettingsButton;
    private TextView toSettingstext;
    private Button usbExchangeButton;
    private TextView usbConnectionText;
    private UsbManager usbManager;
    public static boolean usbConnected = false;
    private StorageManager storageManager;
    private static final Logger logger = Logger.getLogger(UsbFragment.class.getName());
    private final ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

    public UsbFragment(TransportPaths transportPaths) {
        this.transportPaths = transportPaths;
    }

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.usb_fragment, container, false);

        storageManager = (StorageManager) getActivity().getSystemService(Context.STORAGE_SERVICE);
        usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        usbFileManager = new UsbFileManager(storageManager, transportPaths);

        if (isManageAllFilesAccessGranted()) {
            Toast.makeText(requireContext(), "all files can be accesses", Toast.LENGTH_SHORT).show();
            usbExchangeButton = view.findViewById(R.id.usb_exchange_button);
            usbConnectionText = view.findViewById(R.id.usbconnection_response_text);
            usbExchangeButton.setOnClickListener(v -> {
                logger.log(INFO, "Sync button was hit");
                try {
                    usbFileManager.populateUsb();
                } catch (IOException e) {
                    logger.log(INFO, "Populate USB was unsuccessful");
                    throw new RuntimeException(e);
                }
            });
        } else {
            Toast.makeText(requireContext(), "no files can be accesses", Toast.LENGTH_SHORT).show();
            toSettingsButton = view.findViewById(R.id.to_settings_button);
            toSettingstext = view.findViewById(R.id.to_settings_text);
            toSettingsButton.setOnClickListener(v -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                } else {
                    Toast.makeText(requireContext(), "This option is needed/available only on Android 11 or higher.", Toast.LENGTH_SHORT).show();
                }
            });
        }

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    public boolean checkUsbConnection(int tries) {
        usbConnected = !usbManager.getDeviceList().isEmpty();
        if (tries > 0 && !usbConnected) {
            scheduledExecutor.schedule(() -> checkUsbConnection(tries - 1), 1, TimeUnit.SECONDS);
        }
        return usbConnected;
    }

    public boolean isManageAllFilesAccessGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            // For Android versions below 11, this permission doesn't apply.
            return true;
        }
    }
}