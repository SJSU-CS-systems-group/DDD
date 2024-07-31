package net.discdd.bundleclient;

import static java.util.logging.Level.INFO;

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
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class MainPageFragment extends Fragment {

    // gRPC set up
    private Button connectButton;
    private Button exchangeButton;
    private Button usbExchangeButton;
    private Button detectTransportButton;
    private Button receiveFromTransportButton;
    private FileChooserFragment fragment;
    private TextView resultText;
    private TextView connectedDevicesText;
    private TextView wifiDirectResponseText;
    private TextView usbConnectionText;
    private UsbManager usbManager;
    private BroadcastReceiver mUsbReceiver;
    private EditText domainInput;
    private EditText portInput;
    private Button connectServerBtn;
    private StorageManager storageManager;
    private static final String usbDirName = "/DDD_transport";
    private static final Logger logger = Logger.getLogger(BundleClientActivity.class.getName());
    private ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.activity_helloworld, container, false);

        //Initialize UI elements and buttons
        connectButton = view.findViewById(R.id.connect_button);
        exchangeButton = view.findViewById(R.id.exchange_button);
        usbExchangeButton = view.findViewById(R.id.usb_exchange_button);
        resultText = view.findViewById(R.id.grpc_response_text);
        connectedDevicesText = view.findViewById(R.id.connected_device_address);
        wifiDirectResponseText = view.findViewById(R.id.wifidirect_response_text);
        usbConnectionText = view.findViewById(R.id.usbconnection_response_text);
        resultText.setMovementMethod(new ScrollingMovementMethod());
        domainInput = view.findViewById(R.id.domain_input);
        portInput = view.findViewById(R.id.port_input);
        connectServerBtn = view.findViewById(R.id.btn_connect_bundle_server);

        //set button click listeners to interact with the activity
        connectButton.setOnClickListener(v -> {
            if (getActivity() instanceof BundleClientActivity) {
                ((BundleClientActivity) getActivity()).connectTransport();
            }
        });

        exchangeButton.setOnClickListener(v -> {
            if (getActivity() instanceof BundleClientActivity) {
                ((BundleClientActivity) getActivity()).exchangeMessage();
            }
        });

        connectServerBtn.setOnClickListener(v -> {
            if (getActivity() instanceof BundleClientActivity) {
                ((BundleClientActivity) getActivity()).connectToServer(domainInput.getText().toString(),
                                                                       portInput.getText().toString());
            }
        });

        domainInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.length() > 0 && portInput.getText().toString().length() > 0) {
                    connectServerBtn.setEnabled(true);
                } else {
                    connectServerBtn.setEnabled(false);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });

        portInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.length() > 0 && !domainInput.getText().toString().isEmpty()) {
                    connectServerBtn.setEnabled(true);
                } else {
                    connectServerBtn.setEnabled(false);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });

        // register network listeners
        if (getActivity() instanceof BundleClientActivity && !domainInput.getText().toString().isEmpty() &&
                !portInput.getText().toString().isEmpty()) {
            ((BundleClientActivity) getActivity()).createAndRegisterConnectivityManager(
                    domainInput.getText().toString(), portInput.getText().toString());
        }
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
        checkUsbConnection(1);

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
            updateUsbStatus(false, "No USB connection detected\n", Color.RED);
            BundleClientActivity.usbConnected = false;
            showUsbDetachedToast();
        } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            updateUsbStatus(false, "USB device attached. Checking for storage volumes.\n", Color.BLUE);
            scheduledExecutor.schedule(() -> checkUsbConnection(1), 1, TimeUnit.SECONDS);
            showUsbAttachedToast();
        }
    }

    //Method to check intial USB connection
    private void checkUsbConnection(int tries) {
        BundleClientActivity.usbConnected = !usbManager.getDeviceList().isEmpty() && usbDirExists();
        getActivity().getMainExecutor().execute(() -> {
            if (!usbManager.getDeviceList().isEmpty()) {
                if (usbDirExists()) {
                    updateUsbStatus(true, "USB connection detected\n", Color.GREEN);
                } else {
                    updateUsbStatus(false, "USB was connected, but /DDD_transport directory was not detected\n",
                                    Color.RED);
                }
            } else {
                updateUsbStatus(false, "Usb device not connected\n", Color.RED);
            }
        });
        if (tries > 0 && !BundleClientActivity.usbConnected) {
            scheduledExecutor.schedule(() -> checkUsbConnection(tries - 1), 1, TimeUnit.SECONDS);
        }
    }

    //Method to update USB status
    public void updateUsbStatus(boolean isConnected, String statusText, int color) {
        usbExchangeButton.setEnabled(isConnected);
        usbConnectionText.setText(statusText);
        usbConnectionText.setTextColor(color);
    }

    // Method to update connected devices text
    public void updateConnectedDevicesText(List<String> devices) {
        connectedDevicesText.setText("");
        for (String device : devices) {
            connectedDevicesText.append(device + "\n");
        }
    }

    // Method to update Wifi Direct response text
    public void updateWifiDirectResponse(String text) {
        wifiDirectResponseText.append(text);
    }

    // Method to enable/disable connect button
    public void setConnectButtonEnabled(boolean isEnabled) {
        connectButton.setEnabled(isEnabled);
    }

    // Method to enable/disable exchange button
    public void setExchangeButtonEnabled(boolean isEnabled) {
        exchangeButton.setEnabled(isEnabled);
    }

    // Method to set result text
    public void setResultText(String text) {
        resultText.setText(text);
    }

    public void appendResultText(String text) {
        requireActivity().runOnUiThread(() -> resultText.append(text));
    }

    // Method to show USB detached toast
    private void showUsbDetachedToast() {
        Toast.makeText(getActivity(), "USB device detached", Toast.LENGTH_SHORT).show();
    }

    // Method to show USB attached toast
    private void showUsbAttachedToast() {
        Toast.makeText(getActivity(), "USB device attached", Toast.LENGTH_SHORT).show();
    }

    // Method to check if /DDD_transport directory exists
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

    public void setConnectServerBtn(boolean isEnabled) {
        connectServerBtn.setEnabled(isEnabled);
    }
}
