package net.discdd.bundletransport;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.discdd.wifidirect.WifiDirectManager;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class TransportWifiDirectFragment extends Fragment {
    private static final Logger logger = Logger.getLogger(TransportWifiDirectFragment.class.getName());
    private final IntentFilter intentFilter = new IntentFilter();
    private BundleTransportWifiEvent bundleTransportWifiEvent;
    private TextView deviceNameView;
    private TextView myWifiInfoView;
    private TextView clientLogView;
    private View changeDeviceNameView;
    private TextView myWifiStatusView;
    private SharedPreferences sharedPreferences;
    private TransportWifiDirectService btService;

    public TransportWifiDirectFragment() {
        intentFilter.addAction(TransportWifiDirectService.NET_DISCDD_BUNDLETRANSPORT_WIFI_EVENT_ACTION);
        intentFilter.addAction(TransportWifiDirectService.NET_DISCDD_BUNDLETRANSPORT_CLIENT_LOG_ACTION);
    }

    private void processDeviceInfoChange() {
        // NOTE: we aren't using device info here, but be aware that it can be null!
        requireActivity().runOnUiThread(() -> {
            if (btService == null) return;
            var deviceName = btService.getDeviceName();
            deviceNameView.setText(deviceName != null ? deviceName : getString(R.string.unknown));
            // only show the changeDeviceNameView if we don't have a valid device name
            // (transports must have device names starting with ddd_)
            if (deviceName != null) {
                changeDeviceNameView.setVisibility(deviceName.startsWith("ddd_") ? View.GONE : View.VISIBLE);
            }
            var status = btService.getStatus();
            myWifiStatusView.setText(switch (status) {
                case UNDEFINED -> getString(R.string.check_permissions_and_that_wifi_is_enabled);
                case CONNECTED -> getString(R.string.connected);
                case INVITED -> getString(R.string.invited);
                case FAILED -> getString(R.string.failed);
                case AVAILABLE -> getString(R.string.available);
                case UNAVAILABLE -> getString(R.string.unavailable);
            });
        });
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sharedPreferences = requireActivity().getSharedPreferences(TransportWifiDirectService.WIFI_DIRECT_PREFERENCES,
                                                                   Context.MODE_PRIVATE);
    }

    @Override
    public void onResume() {
        super.onResume();
        registerBroadcastReceiver();
        updateGroupInfo();
        processDeviceInfoChange();
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

    private BundleTransportActivity getBundleTransportActivity() {
        return (BundleTransportActivity) requireActivity();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        getBundleTransportActivity().transportWifiServiceConnection.thenAccept(btService -> {
            this.btService = btService;
            processDeviceInfoChange();
            updateGroupInfo();
        });
        // Inflate the layout for this fragment
        View rootView = inflater.inflate(R.layout.fragment_transport_wifi_direct, container, false);
        myWifiInfoView = rootView.findViewById(R.id.my_wifi_info);
        myWifiInfoView.setOnClickListener(v -> {
            var gi = btService.getGroupInfo();
            final var connectedPeers = new ArrayList<String>();
            if (gi != null) {
                gi.getClientList().forEach(c -> connectedPeers.add(c.deviceName));
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(requireContext()).setTitle("Connected clients")
                    .setItems(connectedPeers.toArray(new String[0]), null);
            builder.create().show();
        });
        myWifiStatusView = rootView.findViewById(R.id.my_wifi_status);
        changeDeviceNameView = rootView.findViewById(R.id.change_device_name);
        rootView.findViewById(R.id.name_change_button).setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS);
            startActivity(intent);
        });
        clientLogView = rootView.findViewById(R.id.client_log);
        deviceNameView = rootView.findViewById(R.id.device_name);
        deviceNameView.setText("Unknown");
        myWifiInfoView.setText("Wifi state pending...");
        CheckBox bgWifiCheckbox = rootView.findViewById(R.id.collect_background_data);
        bgWifiCheckbox.setChecked(getBundleTransportActivity().isBackgroundWifiEnabled());
        bgWifiCheckbox.setOnCheckedChangeListener(
                (buttonView, isChecked) -> getBundleTransportActivity().setBgWifiEnabled(isChecked));

        bundleTransportWifiEvent = new BundleTransportWifiEvent();
        return rootView;
    }

    private void registerBroadcastReceiver() {
        LocalBroadcastManager.getInstance(requireActivity()).registerReceiver(bundleTransportWifiEvent, intentFilter);
        updateGroupInfo();
        processDeviceInfoChange();
    }

    private void unregisterBroadcastReceiver() {
        LocalBroadcastManager.getInstance(requireActivity()).unregisterReceiver(bundleTransportWifiEvent);
    }

    private void updateGroupInfo() {
        if (btService != null) {
            var gi = btService.getGroupInfo();
            logger.info("Group info: " + gi);
            requireActivity().runOnUiThread(() -> {
                String info;
                if (gi == null) {
                    info = getString(R.string.wifi_transport_not_active);
                } else {
                    String addresses;
                    try {
                        NetworkInterface ni = NetworkInterface.getByName(gi.getInterface());
                        addresses = ni == null ? "N/A" : ni.getInterfaceAddresses().stream()
                                .filter(ia -> ia.getAddress() instanceof Inet4Address)
                                .map(ia -> ia.getAddress().getHostAddress()).collect(Collectors.joining(", "));
                    } catch (SocketException e) {
                        addresses = "unknown";
                    }
                    info = String.format("SSID: %s\nPassword: %s\nAddress: %s\nConnected devices: %d",
                                         gi.getNetworkName(), gi.getPassphrase(), addresses, gi.getClientList().size());
                }
                myWifiInfoView.setText(info);
            });
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

    class BundleTransportWifiEvent extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() != null && intent.getAction()
                    .equals(TransportWifiDirectService.NET_DISCDD_BUNDLETRANSPORT_CLIENT_LOG_ACTION)) {
                String message = intent.getStringExtra("message");
                appendToClientLog(message);
            } else {
                var actionType = intent.getSerializableExtra("type", WifiDirectManager.WifiDirectEventType.class);
                var actionMessage = intent.getStringExtra("message");
                if (actionType != null) {
                    switch (actionType) {
                        case WIFI_DIRECT_MANAGER_DEVICE_INFO_CHANGED:
                        case WIFI_DIRECT_MANAGER_INITIALIZED:
                            processDeviceInfoChange();
                            updateGroupInfo();
                            break;
                        case WIFI_DIRECT_MANAGER_GROUP_INFO_CHANGED:
                            updateGroupInfo();
                            break;
                    }
                }
            }
        }
    }
}