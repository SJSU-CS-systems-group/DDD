package net.discdd.bundleclient;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.os.Bundle;
import android.os.storage.StorageManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.discdd.wifidirect.WifiDirectManager;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class MainPageFragment extends Fragment {
    private static final Logger logger = Logger.getLogger(MainPageFragment.class.getName());

    private FileChooserFragment fragment;
    private TextView resultText;
    private TextView connectedDeviceText;
    private TextView wifiDirectResponseText;
    private EditText domainInput;
    private EditText portInput;
    private Button connectServerBtn;
    private Button refreshPeersBtn;
    private StorageManager storageManager;
    private static final String usbDirName = "/DDD_transport";
    private RecyclerView peersList;
    private ArrayList<WifiP2pDevice> peers = new ArrayList<>();
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback serverConnectNetworkCallback;
    private WifiDirectManager wifiDirectManager;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.activity_helloworld, container, false);

        //Initialize UI elements and buttons
        resultText = view.findViewById(R.id.grpc_response_text);
        connectedDeviceText = view.findViewById(R.id.connected_device_address);
        wifiDirectResponseText = view.findViewById(R.id.wifidirect_response_text);
        resultText.setMovementMethod(new ScrollingMovementMethod());
        domainInput = view.findViewById(R.id.domain_input);
        portInput = view.findViewById(R.id.port_input);
        connectServerBtn = view.findViewById(R.id.btn_connect_bundle_server);
        peersList = view.findViewById(R.id.peers_list);
        peersList.setLayoutManager(new LinearLayoutManager(getContext()));
        peersList.setAdapter(new RecyclerView.Adapter() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new RecyclerView.ViewHolder(inflater.inflate(R.layout.peers_list_element, parent, false)) {};
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                TextView name = holder.itemView.findViewById(R.id.peer_name);
                name.setText(peers.get(position).deviceName);
                Button action = holder.itemView.findViewById(R.id.peer_exchange);
                action.setOnClickListener(click -> {exchangeMessage(peers.get(position), action);});
            }

            @Override
            public int getItemCount() {
                return peers.size();
            }
        });

        refreshPeersBtn = view.findViewById(R.id.refresh_peers_button);
        refreshPeersBtn.setOnClickListener(v -> ((BundleClientActivity) requireActivity()).refreshPeers());
        connectServerBtn.setOnClickListener(v -> {
            connectToServer(domainInput.getText().toString(), portInput.getText().toString());
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
            createAndRegisterConnectivityManager(domainInput.getText().toString(), portInput.getText().toString());
        }

        return view;
    }

    // Method to update connected devices text
    public void updateConnectedDevices(HashSet<WifiP2pDevice> devices) {
        requireActivity().runOnUiThread(() -> {
            Map<String, WifiP2pDevice> discoveredPeers = devices.stream()
                    // we are only looking for bundle transports, and their names start
                    // with ddd_
                    .filter(d -> d.deviceName.startsWith("ddd_")).collect(Collectors.toMap(d -> d.deviceName, d -> d));
            Map<String, WifiP2pDevice> currentPeers =
                    peers.stream().collect(Collectors.toMap(d -> d.deviceName, d -> d));
            var newNames = new HashSet<>(discoveredPeers.keySet());
            newNames.removeAll(currentPeers.keySet());
            var removedNames = new HashSet<String>(currentPeers.keySet());
            removedNames.removeAll(discoveredPeers.keySet());
            peers.removeIf(device -> removedNames.contains(device.deviceName));
            peers.addAll(newNames.stream().map(discoveredPeers::get).collect(Collectors.toCollection(ArrayList::new)));
            peersList.getAdapter().notifyDataSetChanged();
        });
    }

    // Method to update Wifi Direct response text
    public void updateWifiDirectResponse(String text) {
        requireActivity().runOnUiThread(() -> wifiDirectResponseText.setText(text));
    }

    public void appendResultText(String text) {
        requireActivity().runOnUiThread(() -> {
            if (resultText.getLineCount() > 20) {
                int nl = resultText.getText().toString().indexOf('\n');
                resultText.getEditableText().delete(0, nl + 1);
            }

            resultText.append(text);
        });
    }

    public void setConnectServerBtn(boolean isEnabled) {
        connectServerBtn.setEnabled(isEnabled);
    }

    public void updateOwnerAndGroupInfo(InetAddress groupOwnerAddress, WifiP2pGroup groupInfo) {
        // the groupOwnerAddress doesn't seem to be coming through and the groupInfo owner device
        // name doesn't seem to come through either.
        requireActivity().runOnUiThread(() -> {
            var ownerNameAndAddress =
                    groupInfo == null || groupInfo.getOwner() == null ? getString(R.string.not_connected) : getString(R.string.connected_to_transport);
            connectedDeviceText.setText(ownerNameAndAddress);
        });
    }

    void createAndRegisterConnectivityManager(String serverDomain, String serverPort) {
        connectivityManager = ((BundleClientActivity) requireActivity()).connectivityManager;
        NetworkRequest networkRequest =
                new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).build();

        serverConnectNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                logger.log(INFO, "Available network: " + network.toString());
                logger.log(INFO, "Initiating automatic connection to server");
                connectToServer(serverDomain, serverPort);
            }

            @Override
            public void onLost(Network network) {
                logger.log(WARNING, "Lost network connectivity");
                setConnectServerBtn(false);
            }

            @Override
            public void onUnavailable() {
                logger.log(WARNING, "Unavailable network connectivity");
                setConnectServerBtn(false);
            }

            @Override
            public void onBlockedStatusChanged(Network network, boolean blocked) {
                logger.log(WARNING, "Blocked network connectivity");
                setConnectServerBtn(false);
            }
        };

        connectivityManager.registerNetworkCallback(networkRequest, serverConnectNetworkCallback);

        NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
        if (activeNetwork == null || !activeNetwork.isConnectedOrConnecting()) {
            setConnectServerBtn(false);
        }
    }

    void connectToServer(String serverDomain, String serverPort) {
        if (!serverDomain.isEmpty() && !serverPort.isEmpty()) {
            logger.log(INFO, "Sending to " + serverDomain + ":" + serverPort);
            new GrpcReceiveTask(((BundleClientActivity) requireActivity())).executeInBackground(serverDomain,
                                                                                                Integer.parseInt(
                                                                                                        serverPort));

        }
    }

    public void exchangeMessage(WifiP2pDevice device, Button exchangeButton) {
        BundleClientActivity bundleClientActivity = ((BundleClientActivity) requireActivity());
        wifiDirectManager = bundleClientActivity.wifiDirectManager;
        exchangeButton.setEnabled(false);
        wifiDirectManager.connect(device).thenAccept(c -> {
            String transportName = device.deviceName;
            CompletableFuture<WifiP2pGroup> connectionFuture = new CompletableFuture<>();
            connectionFuture.thenAccept(gi -> {
                updateOwnerAndGroupInfo(wifiDirectManager.getGroupOwnerAddress(), wifiDirectManager.getGroupInfo());
                bundleClientActivity.appendResultsMessage(String.format("Starting transmission to %s", transportName));
                new GrpcReceiveTask(bundleClientActivity).executeInBackground("192.168.49.1", 7777)
                        .thenAccept(result -> {
                            logger.log(INFO, "connection complete!");
                            bundleClientActivity.runOnUiThread(() -> exchangeButton.setEnabled(true));
                            wifiDirectManager.disconnect().thenAccept(rc -> {
                                // if we try to refreshPeers right away, nothing happens,
                                // so we need to wait a second
                                bundleClientActivity.runInXMs(this::refreshPeers, 1000);
                                updateOwnerAndGroupInfo(wifiDirectManager.getGroupOwnerAddress(),
                                                        wifiDirectManager.getGroupInfo());
                            });
                        });

            });

            HashMap<String, ArrayList<CompletableFuture<WifiP2pGroup>>> connectionWaiters =
                    bundleClientActivity.connectionWaiters;
            synchronized (connectionWaiters) {
                connectionWaiters.computeIfAbsent(transportName, k -> new ArrayList<>()).add(connectionFuture);
            }
            WifiP2pGroup groupInfo = wifiDirectManager.getGroupInfo();
            if (WifiDirectManager.WifiDirectStatus.CONNECTED.equals(wifiDirectManager.getStatus()) &&
                    groupInfo != null && groupInfo.getOwner() != null &&
                    groupInfo.getOwner().deviceName.equals(transportName)) {
                connectionFuture.complete(groupInfo);
            }

            // stop trying after 20 seconds
            // we should have already connected by then and the future will be stale but just in
            // case there is a problem we don't want to hang for more than 10 seconds
            ((BundleClientActivity) requireActivity()).runInXMs(() -> connectionFuture.complete(null), 10000);
        });
    }

    void refreshPeers() {
        wifiDirectManager.discoverPeers();
    }

}
