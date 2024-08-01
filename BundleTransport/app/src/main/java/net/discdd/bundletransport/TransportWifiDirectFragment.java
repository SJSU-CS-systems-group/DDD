package net.discdd.bundletransport;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import net.discdd.wifidirect.WifiDirectManager;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class TransportWifiDirectFragment extends Fragment {
    private static final Logger logger =
            Logger.getLogger(TransportWifiDirectFragment.class.getName());
    private final BundleTransportWifiEvent bundleTransportWifiEvent =
            new BundleTransportWifiEvent();
    private final IntentFilter intentFilter = new IntentFilter();
    private SharedPreferences sharedPref;
    private EditText deviceNameView;
    private TextView myWifiInfoView;
    private TextView clientLogView;
    private TransportWifiDirectService btService;
    private String deviceName;
    private final ServiceConnection connection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance.
            var binder = (TransportWifiDirectService.TransportWifiDirectServiceBinder) service;
            btService = binder.getService();
            btService.requestP2PState().thenAccept(state -> {
                getActivity().runOnUiThread(() ->
                myWifiInfoView.setText(state ? "Wifi Ready" : "Wifi not ready"));
            });
            updateGroupInfo();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            btService = null;
        }
    };

    public TransportWifiDirectFragment() {
        intentFilter.addAction(
                TransportWifiDirectService.NET_DISCDD_BUNDLETRANSPORT_WIFI_EVENT_ACTION);
        intentFilter.addAction(
                TransportWifiDirectService.NET_DISCDD_BUNDLETRANSPORT_CLIENT_LOG_ACTION);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        getActivity().registerReceiver(bundleTransportWifiEvent, intentFilter,
                                       Context.RECEIVER_NOT_EXPORTED);

    }

    @Override
    public void onPause() {
        getActivity().unregisterReceiver(bundleTransportWifiEvent);
        super.onPause();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View rootView = inflater.inflate(R.layout.fragment_transport_wifi_direct, container, false);
        myWifiInfoView = rootView.findViewById(R.id.my_wifi_info);
        clientLogView = rootView.findViewById(R.id.client_log);
        deviceNameView = rootView.findViewById(R.id.device_name);
        sharedPref = getContext().getSharedPreferences("wifi_direct", Context.MODE_PRIVATE);
        deviceName = sharedPref.getString("device_name", "BundleTransport");
        myWifiInfoView.setText("Wifi state pending...");

        deviceNameView.setText(deviceName);
        Button saveDeviceName = rootView.findViewById(R.id.save_device_name);
        Button resetDeviceName = rootView.findViewById(R.id.reset_device_name);
        saveDeviceName.setEnabled(false);
        resetDeviceName.setEnabled(false);
        saveDeviceName.setOnClickListener(v -> {
            deviceName = deviceNameView.getText().toString();
            sharedPref.edit().putString("device_name", deviceName).apply();
            btService.removeGroup().thenAccept(b -> {
                btService.setDeviceName(deviceName);
                btService.createGroup();
            });
            saveDeviceName.setEnabled(false);
            resetDeviceName.setEnabled(false);
        });
        resetDeviceName.setOnClickListener(v -> {
            deviceNameView.setText(deviceName);
            saveDeviceName.setEnabled(false);
            resetDeviceName.setEnabled(false);
        });

        deviceNameView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                boolean nameChanged = !deviceNameView.getText().toString().equals(deviceName);
                saveDeviceName.setEnabled(nameChanged);
                resetDeviceName.setEnabled(nameChanged);
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });

        Intent intent = new Intent(getActivity(), TransportWifiDirectService.class);
        getContext().bindService(intent, connection, Context.BIND_AUTO_CREATE);
        getContext().registerReceiver(bundleTransportWifiEvent, intentFilter,
                                      Context.RECEIVER_NOT_EXPORTED);
        return rootView;
    }

    private void updateGroupInfo() {
        btService.requestGroupInfo().thenAccept(gi -> {
            logger.info("Group info: " + gi);
            requireActivity().runOnUiThread(() -> {
                String addresses;
                try {
                    NetworkInterface ni = NetworkInterface.getByName(gi.getInterface());
                    addresses = ni.getInterfaceAddresses().stream()
                            .map(ia -> ia.getAddress().getHostAddress())
                            .collect(Collectors.joining(", "));
                } catch (SocketException e) {
                    addresses = "unknown";
                }
                String info = "SSID: " + gi.getNetworkName() + '\n' + "Passphrase: " +
                        gi.getPassphrase() + '\n' + "Is Group Owner: " + gi.isGroupOwner() + '\n' +
                        "Group Owner Address: " + addresses + '\n';
                myWifiInfoView.setText(info);
            });
        });
    }

    private void appendToClientLog(String message) {
        requireActivity().runOnUiThread(() -> {
            if (clientLogView.getLineCount() > 20) {
                int nl = clientLogView.getText().toString().indexOf('\n');
                clientLogView.getEditableText().delete(0, nl + 1);
            }
            clientLogView.append(message + '\n');
        });
    }

    class BundleTransportWifiEvent extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction()
                    .equals(TransportWifiDirectService.NET_DISCDD_BUNDLETRANSPORT_CLIENT_LOG_ACTION)) {
                String message = intent.getStringExtra("message");
                appendToClientLog(message);
            } else {
                var actionType = intent.getSerializableExtra("action",
                                                             WifiDirectManager.WifiDirectEventType.class);
                var actionMessage = intent.getStringExtra("message");
                switch (actionType) {
                    case WIFI_DIRECT_MANAGER_INITIALIZATION_SUCCESSFUL -> {
                        appendToClientLog("Wifi Direct initialized");
                        logger.info("Wifi Direct initialized");
                        updateGroupInfo();
                    }
                    case WIFI_DIRECT_MANAGER_FORMED_CONNECTION_FAILED -> {
                        appendToClientLog("Wifi Direct connection initiation failed");
                    }
                    case WIFI_DIRECT_MANAGER_FORMED_CONNECTION_SUCCESSFUL -> {
                        appendToClientLog("Wifi Direct connection initiation successful");
                    }
                }
            }

        }

    }
}