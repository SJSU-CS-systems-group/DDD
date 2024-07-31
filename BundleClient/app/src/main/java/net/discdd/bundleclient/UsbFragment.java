package net.discdd.bundleclient;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
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
import java.util.logging.Logger;

public class UsbFragment extends Fragment {
    private Button usbExchangeButton;
    private TextView usbConnectionText;
    private UsbManager usbManager;
    private BroadcastReceiver mUsbReceiver;
    public static boolean usbConnected = false;
    private static final Logger logger = Logger.getLogger(UsbFragment.class.getName());

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
            UsbFragment.usbConnected = false;
            showUsbDetachedToast();
        } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            if (usbDirExists()) {
                updateUsbStatus(true, "USB connection detected\n", Color.GREEN);
                UsbFragment.usbConnected = true;
                showUsbAttachedToast();
            } else {
                updateUsbStatus(false, "USB was connected, but /DDD_transport directory was not detected\n", Color.RED);
                UsbFragment.usbConnected = false;
                showUsbAttachedToast();
            }
        }
    }

    //Method to check intial USB connection
    private void checkUsbConnection() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        if (!deviceList.isEmpty()) {
            if (usbDirExists()) {
                UsbFragment.usbConnected = true;
                updateUsbStatus(true, "USB connection detected\n", Color.GREEN);
                showUsbAttachedToast();
            } else {
                UsbFragment.usbConnected = false;
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

    private void showUsbAttachedToast() {
        Toast.makeText(getActivity(), "USB device attached", Toast.LENGTH_SHORT).show();
    }

    private boolean usbDirExists() {
        // Implement your directory check logic here
        return false;
    }

    private void showUsbDetachedToast() {
        Toast.makeText(getActivity(), "USB device detached", Toast.LENGTH_SHORT).show();
    }
}
