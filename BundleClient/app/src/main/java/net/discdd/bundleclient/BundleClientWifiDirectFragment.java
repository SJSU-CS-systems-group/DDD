package net.discdd.bundleclient;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.wifi.p2p.WifiP2pGroup;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.discdd.client.bundletransmission.BundleTransmission.RecentTransport;
import net.discdd.wifidirect.WifiDirectManager;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class BundleClientWifiDirectFragment extends Fragment {
    private static final Logger logger = Logger.getLogger(BundleClientWifiDirectFragment.class.getName());

    private final BundleClientServiceBroadcastReceiver bundleClientServiceBroadcastReceiver =
            new BundleClientServiceBroadcastReceiver();
    private final IntentFilter intentFilter = new IntentFilter();
    private final ArrayList<String> peerDeviceAddresses = new ArrayList<>();
    private TextView resultText;
    private TextView connectedDeviceText;
    private RecyclerView peersList;
    private TextView clientIdView;
    private SharedPreferences preferences;
    private TextView deliveryStatus;

    public BundleClientWifiDirectFragment() {
        intentFilter.addAction(BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_WIFI_EVENT_ACTION);
        intentFilter.addAction(BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_LOG_ACTION);
    }

    public static BundleClientWifiDirectFragment newInstance() {return new BundleClientWifiDirectFragment();}

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences =
                requireActivity().getSharedPreferences(BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_SETTINGS,
                                                       Context.MODE_PRIVATE);
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.wifi_direct_fragment, container, false);

        //Initialize UI elements and buttons
        clientIdView = view.findViewById(R.id.client_id_text);
        clientIdView.setText(String.format("Client ID: %s", "Service not running"));
        deliveryStatus = view.findViewById(R.id.discovery_status);
        ((BundleClientActivity) requireActivity()).serviceReady.thenAccept(b -> {
            clientIdView.setText(String.format("Client ID: %s", getWifiBgService().getClientId()));
            deliveryStatus.setText(getWifiBgService().isDiscoveryActive() ? "Active" : "Inactive");
        });
        resultText = view.findViewById(R.id.grpc_response_text);
        connectedDeviceText = view.findViewById(R.id.connected_device_address);
        CheckBox bgCheckBox = view.findViewById(R.id.transfer_in_background_checkbox);
        bgCheckBox.setChecked(preferences.getBoolean(
                BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_SETTING_BACKGROUND_EXCHANGE, false));
        bgCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            preferences.edit()
                    .putBoolean(BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_SETTING_BACKGROUND_EXCHANGE,
                                isChecked).apply();
        });
        resultText.setMovementMethod(new ScrollingMovementMethod());
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
                String deviceAddress = peerDeviceAddresses.get(position);
                var peer = getWifiBgService().getPeer(deviceAddress);
                name.setText(peer.getDeviceName());
                name.setOnClickListener(v -> {
                    // fetch the peer again because stats may have changed
                    var updatedPeer = getWifiBgService().getPeer(deviceAddress);
                    new AlertDialog.Builder(requireContext()).setTitle(updatedPeer.getDeviceName()).setMessage(
                            String.format(getString(R.string.PeerDetailedDescription),
                                          getRelativeTime(updatedPeer.getLastSeen()),
                                          getRelativeTime(updatedPeer.getLastExchange()),
                                          getRelativeTime(updatedPeer.getRecencyTime()))).show();
                });
                Button action = holder.itemView.findViewById(R.id.peer_exchange);
                action.setOnClickListener(click -> exchangeMessage(deviceAddress, action));
            }

            @Override
            public int getItemCount() {
                return peerDeviceAddresses.size();
            }
        });

        Button refreshPeersBtn = view.findViewById(R.id.refresh_peers_button);
        refreshPeersBtn.setOnClickListener(v -> discoverPeers());

        return view;
    }

    private String getRelativeTime(long time) {
        if (time == 0) return getString(R.string.never);
        return DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS)
                .toString();
    }

    private void registerBroadcastReceiver() {
        LocalBroadcastManager.getInstance(requireActivity())
                .registerReceiver(bundleClientServiceBroadcastReceiver, intentFilter);
    }

    private void unregisterBroadcastReceiver() {
        LocalBroadcastManager.getInstance(requireActivity()).unregisterReceiver(bundleClientServiceBroadcastReceiver);
    }

    @Override
    public void onResume() {
        super.onResume();
        registerBroadcastReceiver();
    }

    @Override
    public void onPause() {
        unregisterBroadcastReceiver();
        super.onPause();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    private BundleClientWifiDirectService getWifiBgService() {
        return WifiServiceManager.INSTANCE.getService();
    }

    // Method to update connected devices text
    public void updateConnectedDevices() {
        logger.info("UPDATING CONNECTED DEVICES!!!");
        requireActivity().runOnUiThread(() -> {
            var recentTransports = getWifiBgService().getRecentTransports();
            logger.info("Recent transports fetched: " + Arrays.toString(recentTransports));
            var discoveredPeers =
                    Arrays.stream(recentTransports).map(RecentTransport::getDeviceAddress).collect(Collectors.toSet());
            logger.info("discovered peers: " + discoveredPeers);
            var currentPeers = new HashSet<>(peerDeviceAddresses);
            logger.info("current peers: " + currentPeers);
            // figure out the new names (discoveredPeers - currentPeers)
            var newNames = new HashSet<>(discoveredPeers);
            newNames.removeAll(currentPeers);
            // figure out the removed names (currentPeers - discoveredPeers)
            var removedNames = new HashSet<>(currentPeers);
            removedNames.removeAll(discoveredPeers);
            // remove the removed names from the peers list
            peerDeviceAddresses.removeIf(removedNames::contains);
            // add the new names
            logger.info("new names: " + newNames);
            peerDeviceAddresses.addAll(newNames);
            peersList.getAdapter().notifyDataSetChanged();
        });
    }

    public void appendResultText(String text) {
        requireActivity().runOnUiThread(() -> {
            if (resultText.getLineCount() > 20) {
                int nl = resultText.getText().toString().indexOf('\n');
                resultText.getEditableText().delete(0, nl + 1);
            }

            resultText.append(text + "\n");
        });
    }

    public void updateOwnerAndGroupInfo(InetAddress groupOwnerAddress, WifiP2pGroup groupInfo) {
        // the groupOwnerAddress doesn't seem to be coming through and the groupInfo owner device
        // name doesn't seem to come through either.
        requireActivity().runOnUiThread(() -> {
            var ownerNameAndAddress =
                    groupInfo == null || groupInfo.getOwner() == null ? getString(R.string.not_connected) :
                            getString(R.string.connected_to_transport);
            connectedDeviceText.setText(ownerNameAndAddress);
        });
    }

    public void exchangeMessage(String deviceAddress, Button exchangeButton) {
        if (getWifiBgService() != null) {
            exchangeButton.setEnabled(false);
            getWifiBgService().initiateExchange(deviceAddress).thenAccept(c -> exchangeButton.setEnabled(true));
        }
    }

    void discoverPeers() {
        if (getWifiBgService() != null) getWifiBgService().discoverPeers();
    }

    class BundleClientServiceBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            var action = intent.getAction();
            if (action == null) return;
            if (action.equals(BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_LOG_ACTION)) {
                String message = intent.getStringExtra("message");
                appendResultText(message);
            } else if (action.equals(BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_WIFI_EVENT_ACTION)) {
                var wifiEvent = intent.getSerializableExtra(BundleClientWifiDirectService.WIFI_DIRECT_EVENT_EXTRA,
                                                            WifiDirectManager.WifiDirectEventType.class);
                var bundleClientWifiEvent =
                        intent.getSerializableExtra(BundleClientWifiDirectService.BUNDLE_CLIENT_WIFI_EVENT_EXTRA,
                                                    BundleClientWifiDirectService.BundleClientWifiDirectEventType.class);
                if (wifiEvent != null) switch (wifiEvent) {
                    case WIFI_DIRECT_MANAGER_INITIALIZED -> {
                        deliveryStatus.setText(getWifiBgService().isDiscoveryActive() ? "Active" : "Inactive");
                    }
                    case WIFI_DIRECT_MANAGER_PEERS_CHANGED -> updateConnectedDevices();
                    case WIFI_DIRECT_MANAGER_GROUP_INFO_CHANGED, WIFI_DIRECT_MANAGER_DEVICE_INFO_CHANGED ->
                            updateOwnerAndGroupInfo(getWifiBgService().getGroupOwnerAddress(),
                                                    getWifiBgService().getGroupInfo());

                    case WIFI_DIRECT_MANAGER_CONNECTION_CHANGED -> discoverPeers();
                    case WIFI_DIRECT_MANAGER_DISCOVERY_CHANGED -> getActivity().runOnUiThread(
                            () -> deliveryStatus.setText(
                                    getWifiBgService().isDiscoveryActive() ? "Active" : "Inactive"));

                }
                if (bundleClientWifiEvent != null) {
                    var deviceAddress = intent.getStringExtra(
                            BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_DEVICEADDRESS_EXTRA);
                    switch (bundleClientWifiEvent) {
                        case WIFI_DIRECT_CLIENT_EXCHANGE_STARTED -> {
                            var message = String.format("Exchange started with %s", deviceAddress);
                            appendResultText(message);
                        }
                        case WIFI_DIRECT_CLIENT_EXCHANGE_FINISHED -> {
                            var message = String.format("Exchange completed with %s", deviceAddress);
                            appendResultText(message);
                        }
                    }
                }
            }
        }
    }
}
