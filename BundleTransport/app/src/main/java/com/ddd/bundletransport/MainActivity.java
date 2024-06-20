package com.ddd.bundletransport;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;

import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.ddd.bundletransport.utils.FileUtils;
import com.ddd.bundletransport.utils.SecurityUtils;
import com.ddd.wifidirect.WifiDirectManager;
import com.ddd.wifidirect.WifiDirectStateListener;

import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;

import java.io.File;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

public class MainActivity extends AppCompatActivity implements RpcServerStateListener, WifiDirectStateListener {

    private static final Logger logger = Logger.getLogger(MainActivity.class.getName());

    public static final int PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION = 1001;
    // public static final String TAG = "dddTransport";

    private Button startGRPCServerBtn, stopGRPCServerBtn;
    private WifiDirectManager wifiDirectManager;
    private String Receive_Directory;
    private String Server_Directory;
    private String tidPath;
    public static String transportID;

    private String serverDomain;
    private String serverPort;

    private SharedPreferences sharedPref;

    private EditText domainInput;
    private EditText portInput;
    private RpcServer grpcServer;
    private ExecutorService executor = Executors.newFixedThreadPool(2);
    ;
    private TextView serverConnectStatus;

    private TextView connectedPeersText;
    private TextView nearByPeersText;

    private Button connectServerBtn;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback serverConnectNetworkCallback;

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION) {
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                logger.log(SEVERE, "Fine location permission is not granted!");
                finish();
            }
        }
    }

    // methods for managing grpc server
    private void manageRequestedWifiDirectGroup() {
        CompletableFuture<WifiP2pGroup> completedFuture = wifiDirectManager.requestGroupInfo();
        completedFuture.thenApply((b) -> {
            Toast.makeText(this, "I request groupInfo: ", Toast.LENGTH_SHORT).show();
            WifiP2pGroup group = b;
            Collection<WifiP2pDevice> devices = group.getClientList();
            logger.log(FINE, "Looping through group devices");
            for (WifiP2pDevice d : devices) {
                Log.d(TAG, d.toString());
            }
            return b;
        });
    }

    private void startRpcServer() {
        executor.execute(() -> {
            synchronized (grpcServer) {
                if (grpcServer.isShutdown()) {
                    manageRequestedWifiDirectGroup();
                    logger.log(INFO, "starting grpc server from main activity!!!!!!!");
                    grpcServer.startServer(this);
                }
            }
        });
    }

    private void stopRpcServer() {
        executor.execute(() -> {
            synchronized (grpcServer) {
                if (!grpcServer.isShutdown()) {
                    grpcServer.shutdownServer();
                }
            }
        });
    }

    @Override
    public void onStateChanged(RpcServer.ServerState newState) {
        runOnUiThread(() -> {
            TextView grpcServerState = findViewById(R.id.grpc_server_state);
            if (newState == RpcServer.ServerState.RUNNING) {
                grpcServerState.setText("GRPC Server State: RUNNING");
                startGRPCServerBtn.setEnabled(false);
                stopGRPCServerBtn.setEnabled(true);
            } else if (newState == RpcServer.ServerState.PENDING) {
                grpcServerState.setText("GRPC Server State: PENDING");
                startGRPCServerBtn.setEnabled(false);
                stopGRPCServerBtn.setEnabled(false);
            } else {
                grpcServerState.setText("GRPC Server State: SHUTDOWN");
                startGRPCServerBtn.setEnabled(true);
                stopGRPCServerBtn.setEnabled(false);
            }
        });
    }

    // utils
    private void toggleBtnEnabled(Button btn, boolean enable) {
        runOnUiThread(() -> {
            btn.setEnabled(enable);
        });
    }

    // methods for managing bundle server requests
    private Void connectToServerComplete(Void x) {
        toggleBtnEnabled(connectServerBtn, true);
        return null;
    }

    private void connectToServer() {

        serverDomain = domainInput.getText().toString();
        serverPort = portInput.getText().toString();
        if (!serverDomain.isEmpty() && !serverPort.isEmpty()) {
            logger.log(INFO, "Sending to " + serverDomain + ":" + serverPort);

            runOnUiThread(() -> {
                serverConnectStatus.setText(
                        "Initiating server exchange to " + serverDomain + ":" + serverPort + "...\n");
            });

            ServerManager serverManager =
                    new ServerManager(this.getExternalFilesDir(null), serverDomain, serverPort, transportID,
                                      this::sendTask, this::receiveTask, this::connectToServerComplete);
            executor.execute(serverManager);
        } else {
            Toast.makeText(MainActivity.this, "Enter the domain and port", Toast.LENGTH_SHORT).show();
        }
    }

    private void createAndRegisterConnectivityManager() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest networkRequest =
                new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).build();

        serverConnectNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                logger.log(INFO, "Available network: " + network.toString());
                logger.log(INFO, "Initiating automatic connection to server");
                connectToServer();
            }

            @Override
            public void onLost(Network network) {
                logger.log(WARNING, "Lost network connectivity");
//                toggleBtnEnabled(connectServerBtn, false);
                connectServerBtn.setEnabled(false);
            }

            @Override
            public void onUnavailable() {
                logger.log(WARNING, "Unavailable network connectivity");
//                toggleBtnEnabled(connectServerBtn, false);
                connectServerBtn.setEnabled(false);
            }

            @Override
            public void onBlockedStatusChanged(Network network, boolean blocked) {
                logger.log(WARNING, "Blocked network connectivity");
//                toggleBtnEnabled(connectServerBtn, false);
                connectServerBtn.setEnabled(false);

            }
        };

        connectivityManager.registerNetworkCallback(networkRequest, serverConnectNetworkCallback);

        NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
        if (activeNetwork == null || !activeNetwork.isConnectedOrConnecting()) {
//            toggleBtnEnabled(connectServerBtn, false);
            connectServerBtn.setEnabled(false);

        }
    }

    private Void sendTask(Exception thrown) {
        runOnUiThread(() -> {
            if (thrown != null) {
                serverConnectStatus.append("Bundles upload failed.\n");
                Log.e(TAG, "Failed bundle upload, exception: " + thrown.getMessage());
            } else {
                serverConnectStatus.append("Bundles uploaded successfully.\n");
            }
        });

        return null;
    }

    private Void receiveTask(Exception thrown) {
        runOnUiThread(() -> {
            if (thrown != null) {
                serverConnectStatus.append("Bundles download failed.\n");
                Log.e(TAG, "Failed bundle d, exception: " + thrown.getMessage());
            } else {
                serverConnectStatus.append("Bundles downloaded successfully.\n");
            }
        });

        return null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        startGRPCServerBtn = findViewById(R.id.btn_start_rpc_server);
        stopGRPCServerBtn = findViewById(R.id.btn_stop_rpc_server);
        domainInput = findViewById(R.id.domain_input);
        portInput = findViewById(R.id.port_input);
        serverConnectStatus = findViewById(R.id.server_connection_status);
        connectServerBtn = findViewById(R.id.btn_connect_bundle_server);
        connectedPeersText = findViewById(R.id.connected_peers);
        nearByPeersText = findViewById(R.id.nearby_peers);

        // retrieve domain and port from shared preferences
        // populate text inputs if data is retrieved
        sharedPref = getSharedPreferences("server_endpoint", MODE_PRIVATE);
        restoreDomainPort();

        String SERVER_BASE_PATH = this.getExternalFilesDir(null) + "/BundleTransmission";
        Receive_Directory = SERVER_BASE_PATH + "/client";
        Server_Directory = SERVER_BASE_PATH + "/server";
        wifiDirectManager = new WifiDirectManager(this.getApplication(), this.getLifecycle(), this);
        wifiDirectManager.initialize();

        grpcServer = RpcServer.getInstance(this);
        startRpcServer();

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
                if (s.length() > 0 && domainInput.getText().toString().length() > 0) {
                    connectServerBtn.setEnabled(true);
                } else {
                    connectServerBtn.setEnabled(false);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });

        // set up transport Id
        tidPath = getApplicationContext().getApplicationInfo().dataDir + "/transportIdentity.pub";
        File tid = new File(tidPath);
        if (!tid.exists()) {
            ECKeyPair identityKeyPair = Curve.generateKeyPair();
            try {
                SecurityUtils.createEncodedPublicKeyFile(identityKeyPair.getPublicKey(), tidPath);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            try {
                transportID = SecurityUtils.generateID(tidPath);
                Log.d(TAG, "Transport ID : " + transportID);
            } catch (IOException | InvalidKeyException | NoSuchAlgorithmException e) {
                e.printStackTrace();
            }
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION },
                               MainActivity.PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION);
        }

        // register network listeners
        createAndRegisterConnectivityManager();

        startGRPCServerBtn.setOnClickListener(v -> {
            startRpcServer();
        });

        stopGRPCServerBtn.setOnClickListener(v -> {
            stopRpcServer();
        });

        findViewById(R.id.btn_clear_storage).setOnClickListener(v -> {
            FileUtils.deleteBundles(Receive_Directory);
            FileUtils.deleteBundles(Server_Directory);
        });

        // connect to server
        connectServerBtn.setOnClickListener(view -> {
            connectToServer();
        });

        // save the domain and port inputs
        findViewById(R.id.save_domain_port).setOnClickListener(view -> {
            saveDomainPort();
            Toast.makeText(MainActivity.this, "Saved", Toast.LENGTH_SHORT).show();
        });

        // set saved domain and port to inputs
        findViewById(R.id.restore_domain_port).setOnClickListener(view -> {
            restoreDomainPort();
        });
    }

    private void restoreDomainPort() {
        domainInput.setText(sharedPref.getString("domain", ""));
        portInput.setText(sharedPref.getString("port", ""));
    }

    private void saveDomainPort() {
        SharedPreferences.Editor editor = sharedPref.edit();
        String domain = domainInput.getText().toString();
        String port = portInput.getText().toString();

        editor.putString("domain", domain);
        editor.putString("port", port);
        editor.apply();
    }

    @Override
    protected void onDestroy() {
        Log.i("onDestroy Receiver", "Called");

        super.onDestroy();
        stopRpcServer();

        //connectivityManager.unregisterNetworkCallback(serverConnectNetworkCallback);
        executor.shutdown();
    }

    private void updateConnectedDevices() {
        updateNearbyDevices();
        connectedPeersText.setText("");
        if (wifiDirectManager.getConnectedPeers() != null) {
            Log.d(TAG, "Connected Devices Updates\n");
            wifiDirectManager.getConnectedPeers().stream().forEach(device -> {
                connectedPeersText.append(device.deviceName + "\n");
            });
        }

    }

    private void updateNearbyDevices() {
        nearByPeersText.setText("");
        HashSet<String> nearbyDevicesSet = new HashSet<>();
        HashSet<String> connectedDevicesSet = new HashSet<>();
        if (wifiDirectManager.getConnectedPeers() != null && !wifiDirectManager.getConnectedPeers().isEmpty()) {
            for (WifiP2pDevice p2pDevice : wifiDirectManager.getConnectedPeers()) {
                connectedDevicesSet.add(p2pDevice.deviceName);
            }
        }
        if (wifiDirectManager.getPeerList() != null) {
            for (WifiP2pDevice p2pDevice : wifiDirectManager.getPeerList()) {
                if (!connectedDevicesSet.contains(p2pDevice.deviceName)) {
                    nearbyDevicesSet.add(p2pDevice.deviceName);
                }
            }
        }
        Log.d(TAG, "Nearby Devices Updates\n");
        nearbyDevicesSet.stream().forEach(deviceName -> {
            nearByPeersText.append(deviceName + "\n");
        });
    }

    @Override
    public void onReceiveAction(WifiDirectManager.WIFI_DIRECT_ACTIONS action) {
        runOnUiThread(() -> {
            if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_PEERS_CHANGED == action) {
                updateNearbyDevices();
            } else if (WifiDirectManager.WIFI_DIRECT_ACTIONS.WIFI_DIRECT_MANAGER_FORMED_CONNECTION_SUCCESSFUL ==
                    action) {
                updateConnectedDevices();
            }
        });
    }
}