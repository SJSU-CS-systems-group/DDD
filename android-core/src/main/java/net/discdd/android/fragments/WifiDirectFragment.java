package net.discdd.android.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.discdd.android_core.R;

import net.discdd.wifidirect.WifiDirectManager;
import net.discdd.wifidirect.WifiDirectManager.WifiDirectEvent;
import net.discdd.wifidirect.WifiDirectStateListener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link WifiDirectFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class WifiDirectFragment extends Fragment implements WifiDirectStateListener {
    static final Logger logger = Logger.getLogger(WifiDirectFragment.class.getName());
    private static final String ARG_PARAM1 = "isGroupOwner";
    private boolean isGroupOwner;
    private WifiDirectManager wifiDirectManager;
    private TextView initializedIndicator;
    private TextView status;
    private RecyclerView peersList;
    private WifiPeerViewHolderAdapter listAdapter;

    public WifiDirectFragment() {
        // Required empty public constructor
    }

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @param isGroupOwner true if this fragment is used by the Wifi Direct Group Owner.
     * @return A new instance of fragment WifiDirectFragment.
     */
    // TODO: Rename and change types and number of parameters
    public static WifiDirectFragment newInstance(boolean isGroupOwner) {
        WifiDirectFragment fragment = new WifiDirectFragment();
        Bundle args = new Bundle();
        args.putBoolean(ARG_PARAM1, isGroupOwner);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            isGroupOwner = getArguments().getBoolean(ARG_PARAM1);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        var layout = inflater.inflate(R.layout.wifidirect_fragment, container, false);
        this.initializedIndicator = layout.findViewById(R.id.WifiDirectInitializedIndicator);
        this.status = layout.findViewById(R.id.WifiDirectStatus);
        this.peersList = layout.findViewById(R.id.WifiDirectLayout);
        layout.findViewById(R.id.WifiDirectRefreshButton).setOnClickListener(v -> {
            status.setText("Discovering peers");
            wifiDirectManager.discoverPeers();
        });
        peersList.setLayoutManager(new LinearLayoutManager(getContext()));

        layout.findViewById(R.id.WifiDirectCreateGroupButton).setOnClickListener(v -> {
            status.setText("Creating group");
            wifiDirectManager.createGroup();
        });
        this.listAdapter = new WifiPeerViewHolderAdapter();
        logger.info("************ adding adapter to list");
        this.peersList.setAdapter(listAdapter);
        wifiDirectManager = new WifiDirectManager(getContext(), getLifecycle(), this, "random_name", isGroupOwner);
        wifiDirectManager.initialize();
        return layout;
    }

    @Override
    public void onReceiveAction(WifiDirectEvent action) {
        switch (action.type()) {
            case WIFI_DIRECT_MANAGER_INITIALIZATION_FAILED:
                initializedIndicator.setText("❌");
                getActivity().runOnUiThread(() -> status.setText("Initialization failed"));
                break;
            case WIFI_DIRECT_MANAGER_INITIALIZATION_SUCCESSFUL:
                initializedIndicator.setText("✔");
                getActivity().runOnUiThread(() -> status.setText("Initialization successful"));
                break;
            case WIFI_DIRECT_MANAGER_DISCOVERY_SUCCESSFUL:
                listAdapter.setEmptyMessages("No peers found yet..");
                break;
            case WIFI_DIRECT_MANAGER_DISCOVERY_FAILED:
                listAdapter.setEmptyMessages("Discovery failed" + action.message());
                getActivity().runOnUiThread(() -> status.setText("Discovery failed: " + action.message()));
                break;
            case WIFI_DIRECT_MANAGER_CONNECTION_INITIATION_SUCCESSFUL:
                break;
            case WIFI_DIRECT_MANAGER_FORMED_CONNECTION_SUCCESSFUL:
                getActivity().runOnUiThread(() -> status.setText(action.message()));
                break;
            case WIFI_DIRECT_MANAGER_FORMED_CONNECTION_FAILED:
                getActivity().runOnUiThread(() -> status.setText(action.message()));
                break;
            case WIFI_DIRECT_MANAGER_INFO:
                break;
            case WIFI_DIRECT_MANAGER_PEERS_CHANGED:
                logger.info("New list of peers: " + wifiDirectManager.getDevicesFound());
                getActivity().runOnUiThread(() -> {
                    listAdapter.updatePeers(wifiDirectManager.getDevicesFound());
                    status.setText(wifiDirectManager.getDevicesFound().size() + " peers found");
                });
                break;
            case WIFI_DIRECT_MANAGER_CONNECTION_INITIATION_FAILED:
                break;

        }
    }

    static class WifiPeerViewHolder extends RecyclerView.ViewHolder {
        TextView deviceType;
        TextView deviceName;
        Button deviceAction;

        public WifiPeerViewHolder(@NonNull View itemView) {
            super(itemView);
            deviceName = itemView.findViewById(R.id.WifiDirectItemName);
            deviceType = itemView.findViewById(R.id.WifiDirectItemTypeIndicator);
            deviceAction = itemView.findViewById(R.id.WifiDirectItemButton);
        }
    }

    private class WifiPeerViewHolderAdapter extends RecyclerView.Adapter<WifiPeerViewHolder> {
        private final ArrayList<WifiPeer> peers = new ArrayList<>();
        private String emptyMessage = "Hit refresh to discover peers";

        @NonNull
        @Override
        public WifiPeerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new WifiPeerViewHolder(
                    WifiDirectFragment.this.getLayoutInflater().inflate(R.layout.wifidirect_listitem, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull WifiPeerViewHolder holder, int position) {
            if (peers.isEmpty()) {
                holder.deviceName.setText(emptyMessage);
                holder.deviceType.setText("");
                holder.deviceAction.setVisibility(View.INVISIBLE);
                return;
            }
            holder.deviceName.setText(peers.get(position).name());
            holder.deviceType.setText(peers.get(position).type());
        }

        @Override
        public int getItemCount() {
            logger.log(Level.INFO, "Peers list size: " + peers.size());
            // in the empty case, we still want to show an inprogress entry
            return peers.isEmpty() ? 1 : peers.size();
        }

        void updatePeers(HashSet<String> deviceSet) {
            // remove peers that are not in the deviceSet
            var peersIterator = peers.iterator();
            while (peersIterator.hasNext()) {
                var peer = peersIterator.next();
                if (!deviceSet.contains(peer.name())) {
                    peersIterator.remove();
                } else {
                    deviceSet.remove(peer.name());
                }
            }
            // add peers that are still left in the deviceSet to the beginning
            // of the peers list
            for (var device : deviceSet) {
                peers.add(0, new WifiPeer(device, "Peer"));
            }
            notifyDataSetChanged();
        }

        public void setEmptyMessages(String emptyMessage) {this.emptyMessage = emptyMessage;}

        record WifiPeer(String name, String type) {}
    }
}

