package net.discdd.bundletransport;

import static java.util.logging.Level.INFO;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import net.discdd.bundlerouting.service.FileServiceImpl;
import net.discdd.wifidirect.WifiDirectManager;
import net.discdd.wifidirect.WifiDirectStateListener;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class TransportWifiDirectFragment extends Fragment
        implements WifiDirectStateListener, RpcServerStateListener,
        FileServiceImpl.FileServiceEventListener {
    private static final Logger logger =
            Logger.getLogger(TransportWifiDirectFragment.class.getName());

    private SharedPreferences sharedPref;
    private EditText deviceNameView;
    private WifiDirectManager wifiDirectManager;
    private String deviceName;
    private TextView myWifiInfoView;
    private TextView clientLogView;
    private RpcServer grpcServer = new RpcServer(this);

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        myWifiInfoView.setText("Wifi not initialized");
        if (wifiDirectManager == null) {
            wifiDirectManager =
                    new WifiDirectManager(getContext(), getLifecycle(), this, deviceName, true);
            wifiDirectManager.initialize();
            wifiDirectManager.createGroup();
        }

        deviceNameView.setText(deviceName);
        Button saveDeviceName = rootView.findViewById(R.id.save_device_name);
        Button resetDeviceName = rootView.findViewById(R.id.reset_device_name);
        saveDeviceName.setEnabled(false);
        resetDeviceName.setEnabled(false);
        saveDeviceName.setOnClickListener(v -> {
            deviceName = deviceNameView.getText().toString();
            sharedPref.edit().putString("device_name", deviceName).apply();
            wifiDirectManager.removeGroup().thenAccept(b -> {
                wifiDirectManager.setDeviceName(deviceName);
                wifiDirectManager.createGroup();
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
        return rootView;
    }

    @Override
    public void onReceiveAction(WifiDirectManager.WifiDirectEvent action) {
        switch (action.type()) {
            case WIFI_DIRECT_MANAGER_INITIALIZATION_SUCCESSFUL -> {
                appendToClientLog("Wifi Direct initialized");
                logger.info("Wifi Direct initialized");
                wifiDirectManager.requestGroupInfo().thenAccept(gi -> {
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
                                gi.getPassphrase() + '\n' + "Is Group Owner: " + gi.isGroupOwner() +
                                '\n' + "Group Owner Address: " + addresses + '\n' +
                                "Client List: " + gi.getClientList() + '\n';
                        myWifiInfoView.setText(info);
                    });
                });
            }
            case WIFI_DIRECT_MANAGER_CONNECTION_INITIATION_FAILED -> {
                appendToClientLog("Wifi Direct connection initiation failed");
                logger.info("Wifi Direct connection initiation failed");
            }
            case WIFI_DIRECT_MANAGER_CONNECTION_INITIATION_SUCCESSFUL -> {
                startRpcServer();
            }
        }
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

    private void startRpcServer() {
        synchronized (grpcServer) {
            if (grpcServer.isShutdown()) {
                appendToClientLog("Starting gRPC server");
                logger.log(INFO, "starting grpc server from main activity!!!!!!!");
                grpcServer.startServer(getContext(), this);
            }
        }
    }

    private void stopRpcServer() {
        synchronized (grpcServer) {
            if (!grpcServer.isShutdown()) {
                appendToClientLog("Shutting down gRPC server");
                grpcServer.shutdownServer();
            }
        }

    }

    @Override
    public void onStateChanged(RpcServer.ServerState newState) {
    }

    @Override
    public void onFileServiceEvent(FileServiceImpl.FileServiceEvent fileServiceEvent) {
        appendToClientLog("File service event: " + fileServiceEvent);
    }
}