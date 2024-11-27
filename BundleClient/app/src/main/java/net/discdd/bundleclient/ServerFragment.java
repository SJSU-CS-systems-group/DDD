package net.discdd.bundleclient;

import static android.content.Context.MODE_PRIVATE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.logging.Logger;

public class ServerFragment extends Fragment {
    private static final Logger logger = Logger.getLogger(ServerFragment.class.getName());
    private EditText domainInput;
    private EditText portInput;
    private SharedPreferences sharedPref;

    public static ServerFragment newInstance() {return new ServerFragment();}

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.server_fragment, container, false);
        domainInput = view.findViewById(R.id.domain_input);
        portInput = view.findViewById(R.id.port_input);
        var connectServerBtn = view.findViewById(R.id.btn_connect_client_to_bundle_server);
        Button saveDomainPortBtn = view.findViewById(R.id.save_domain_port);

        sharedPref =
                requireActivity().getSharedPreferences(BundleClientWifiDirectService.NET_DISCDD_BUNDLECLIENT_SETTINGS,
                                                       MODE_PRIVATE);
        restoreDomainPort();

        domainInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                connectServerBtn.setEnabled(s.length() > 0 && portInput.getText().toString().length() > 0);
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
                connectServerBtn.setEnabled(s.length() > 0 && !domainInput.getText().toString().isEmpty());
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });

        saveDomainPortBtn.setOnClickListener(v -> {
            saveDomainPort();
        });

        connectServerBtn.setOnClickListener(v -> {
            connectServerBtn.setEnabled(false);
            ((BundleClientActivity) requireActivity()).wifiBgService.initiateServerExchange()
                    .thenAccept(bec -> requireActivity().runOnUiThread(() -> {
                        Toast.makeText(requireContext(), String.format("%d bundles sent %d received", bec.bundlesSent(),
                                                                       bec.bundlesReceived()), Toast.LENGTH_LONG)
                                .show();
                        connectServerBtn.setEnabled(true);
                    }));
        });

        // register network listeners
        if (getActivity() instanceof BundleClientActivity && !domainInput.getText().toString().isEmpty() &&
                !portInput.getText().toString().isEmpty()) {
            createAndRegisterConnectivityManager(domainInput.getText().toString(), portInput.getText().toString());
        }

        return view;
    }

    private void createAndRegisterConnectivityManager(String serverDomain, String serverPort) {
        ConnectivityManager connectivityManager = ((BundleClientActivity) requireActivity()).connectivityManager;
        NetworkRequest networkRequest =
                new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).build();

        ConnectivityManager.NetworkCallback serverConnectNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                logger.log(INFO, "Available network: " + network.toString());
            }

            @Override
            public void onLost(Network network) {
                logger.log(WARNING, "Lost network connectivity");
            }

            @Override
            public void onUnavailable() {
                logger.log(WARNING, "Unavailable network connectivity");
            }

            @Override
            public void onBlockedStatusChanged(Network network, boolean blocked) {
                logger.log(WARNING, "Blocked network connectivity");
            }
        };

        connectivityManager.registerNetworkCallback(networkRequest, serverConnectNetworkCallback);
    }

    private void saveDomainPort() {
        SharedPreferences.Editor editor = sharedPref.edit();
        String domain = domainInput.getText().toString();
        String port = portInput.getText().toString();

        editor.putString("domain", domain);
        editor.putInt("port", Integer.parseInt(port));
        editor.apply();
    }

    private void restoreDomainPort() {
        domainInput.setText(sharedPref.getString("domain", ""));
        portInput.setText(Integer.toString(sharedPref.getInt("port", 0)));
    }

}
