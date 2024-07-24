package net.discdd.bundleclient;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.HashMap;
import java.util.List;

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


    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
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

        usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
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
        checkUsbConnection();

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
        }
        else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            if (usbDirExists()) {
                updateUsbStatus(true, "USB connection detected\n", Color.GREEN);
                BundleClientActivity.usbConnected = true;
                showUsbAttachedToast();
            }
            else {
                updateUsbStatus(false, "USB was connected, but /DDD_transport directory was not detected\n", Color.RED);
                BundleClientActivity.usbConnected = false;
                showUsbAttachedToast();
            }
        }
    }

    //Method to check intial USB connection
    private void checkUsbConnection() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        if (!deviceList.isEmpty()) {
            if (usbDirExists()) {
                BundleClientActivity.usbConnected = true;
                updateUsbStatus(true, "USB connection detected\n", Color.GREEN);
                showUsbAttachedToast();
            } else {
                BundleClientActivity.usbConnected = false;
                updateUsbStatus(false, "USB was connected, but /DDD_transport directory was not detected\n", Color.RED);
                showUsbAttachedToast();
            }
        } else {
            updateUsbStatus(false, "Usb device not connected\n", Color.RED);
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

    // Method to show USB detached toast
    private void showUsbDetachedToast() {
        Toast.makeText(getActivity(), "USB device detached", Toast.LENGTH_SHORT).show();
    }

    // Method to show USB attached toast
    private void showUsbAttachedToast() {
        Toast.makeText(getActivity(), "USB device attached", Toast.LENGTH_SHORT).show();
    }

    // Method to check if the USB directory exists
    private boolean usbDirExists() {
        // Implement your directory check logic here
        return false;
    }
}
