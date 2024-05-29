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
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.ddd.wifidirect.WifiDirectManager;

import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;

import java.io.File;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements RpcServerStateListener {

    private static final int PORT = 7777;
    public static final int PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION = 1001;
    public static final String TAG = "dddTransport";

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
    private Button connectServerBtn;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback serverConnectNetworkCallback;

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION) {
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Fine location permission is not granted!");
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
            Log.d(TAG, "Looping through group devices");
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
                    Log.d(TAG, "starting grpc server!!!!!!!");
                    grpcServer.startServer(this, PORT);
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
            Log.d(TAG, "Sending to " + serverDomain + ":" + serverPort);

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
                Log.d(TAG, "Available network: " + network.toString());
                Log.d(TAG, "Initiating automatic connection to server");
                connectToServer();
            }

            @Override
            public void onLost(Network network) {
                Log.d(TAG, "Lost network connectivity");
//                toggleBtnEnabled(connectServerBtn, false);
                connectServerBtn.setEnabled(false);
            }

            @Override
            public void onUnavailable() {
                Log.e(TAG, "Unavailable network connectivity");
//                toggleBtnEnabled(connectServerBtn, false);
                connectServerBtn.setEnabled(false);
            }

            @Override
            public void onBlockedStatusChanged(Network network, boolean blocked) {
                Log.d(TAG, "Blocked network connectivity");
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

        // retrieve domain and port from shared preferences
        // populate text inputs if data is retrieved
        sharedPref = getSharedPreferences("server_endpoint", MODE_PRIVATE);
        restoreDomainPort();

        String SERVER_BASE_PATH = this.getExternalFilesDir(null) + "/BundleTransmission";
        Receive_Directory = SERVER_BASE_PATH + "/client";
        Server_Directory = SERVER_BASE_PATH + "/server";
        wifiDirectManager = new WifiDirectManager(this.getApplication(), this.getLifecycle());
        wifiDirectManager.initialize();

        grpcServer = new RpcServer(this);
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
}