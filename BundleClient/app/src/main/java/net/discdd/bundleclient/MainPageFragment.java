package net.discdd.bundleclient;

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


import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class MainPageFragment extends Fragment {
    private static final Logger logger = Logger.getLogger(BundleClientActivity.class.getName());

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

        refreshPeersBtn = view.findViewById(R.id.refresh_peers_button);
        refreshPeersBtn.setOnClickListener(v -> ((BundleClientActivity) requireActivity()).refreshPeers());
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
        var ownerNameAndAddress =
                groupInfo == null || groupInfo.getOwner() == null ? "Not connected" : "Connected to transport";
        connectedDeviceText.setText(ownerNameAndAddress);
    }

}
