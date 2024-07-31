package net.discdd.bundleclient;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.wifi.p2p.WifiP2pDevice;
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
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class MainPageFragment extends Fragment {

    // gRPC set up
    private Button usbExchangeButton;
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
    private RecyclerView peersList;
    private ArrayList<WifiP2pDevice> peers = new ArrayList<>();
    private ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.activity_helloworld, container, false);

        //Initialize UI elements and buttons
        usbExchangeButton = view.findViewById(R.id.usb_exchange_button);
        resultText = view.findViewById(R.id.grpc_response_text);
        connectedDevicesText = view.findViewById(R.id.connected_device_address);
        wifiDirectResponseText = view.findViewById(R.id.wifidirect_response_text);
        usbConnectionText = view.findViewById(R.id.usbconnection_response_text);
        resultText.setMovementMethod(new ScrollingMovementMethod());
        domainInput = view.findViewById(R.id.domain_input);
        portInput = view.findViewById(R.id.port_input);
        connectServerBtn = view.findViewById(R.id.btn_connect_bundle_server);
        peersList = view.findViewById(R.id.peers_list);
        peersList.setLayoutManager(new LinearLayoutManager(getContext()));
        peersList.setAdapter(new RecyclerView.Adapter() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(
                    @NonNull ViewGroup parent, int viewType) {
                return new RecyclerView.ViewHolder(inflater.inflate(R.layout.peers_list_element, parent, false)) {
                };
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                TextView name = holder.itemView.findViewById(R.id.peer_name);
                name.setText(peers.get(position).deviceName);
                Button action = holder.itemView.findViewById(R.id.peer_exchange);
                action.setOnClickListener(click -> {
                    if (getActivity() instanceof BundleClientActivity) {
                        ((BundleClientActivity) getActivity()).exchangeMessage(peers.get(position), action);
                    }
                });
            }

            @Override
            public int getItemCount() {
                return peers.size();
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
    public void updateConnectedDevices(HashSet<WifiP2pDevice> devices) {
        requireActivity().runOnUiThread(() -> {
            Map<String, WifiP2pDevice> discoveredPeers =
                    devices.stream().collect(Collectors.toMap(d -> d.deviceName, d -> d));
            Map<String, WifiP2pDevice> currentPeers =
                    peers.stream().collect(Collectors.toMap(d -> d.deviceName, d -> d));
            var newNames = new HashSet<>(discoveredPeers.keySet());
            newNames.removeAll(currentPeers.keySet());
            var removedNames = new HashSet<String>(currentPeers.keySet());
            removedNames.removeAll(discoveredPeers.keySet());
            peers.removeIf(device -> removedNames.contains(device.deviceName));
            peers.addAll(newNames.stream().map(discoveredPeers::get)
                                 .collect(Collectors.toCollection(ArrayList::new)));
            peersList.getAdapter().notifyDataSetChanged();
        });
    }

    // Method to update Wifi Direct response text
    public void updateWifiDirectResponse(String text) {
        requireActivity().runOnUiThread(() -> wifiDirectResponseText.setText(text));
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
