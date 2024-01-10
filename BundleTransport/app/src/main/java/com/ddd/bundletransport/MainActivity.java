package com.ddd.bundletransport;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;


import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.impl.model.Preference;

import com.ddd.bundletransport.service.BundleDownloadRequest;
import com.ddd.bundletransport.service.BundleDownloadResponse;
import com.ddd.bundletransport.service.BundleMetaData;
import com.ddd.bundletransport.service.BundleServiceGrpc;
import com.ddd.bundletransport.service.BundleUploadObserver;
import com.ddd.bundletransport.service.BundleUploadRequest;
import com.ddd.wifidirect.WifiDirectManager;
import com.google.protobuf.ByteString;

import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

public class MainActivity extends AppCompatActivity implements RpcServerStateListener{

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
    private ExecutorService executor = Executors.newFixedThreadPool(2);;
    private TextView serverConnectStatus;


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION) {
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Fine location permission is not granted!");
                finish();
            }
        }
    }

    private void startRpcWorkerService() {
        CompletableFuture<WifiP2pGroup> completedFuture = wifiDirectManager.requestGroupInfo();
        completedFuture.thenApply((b) -> {
            Toast.makeText(this,
                    "I request groupInfo: ", Toast.LENGTH_SHORT).show();
            WifiP2pGroup group = b;
            Collection<WifiP2pDevice> devices = group.getClientList();
            Log.d(TAG, "Looping through group devices");
            for(WifiP2pDevice d: devices) {
                Log.d(TAG, d.toString());
            }
            return b;
        });
    }

    private void stopRpcWorkerService() {
        WorkManager.getInstance(this).cancelUniqueWork(TAG);
        Toast.makeText(this, "Stop Rpc Server", Toast.LENGTH_SHORT).show();
    }


    @Override
    public void onStateChanged(RpcServer.ServerState newState){
        runOnUiThread(() -> {
            TextView grpcServerState = findViewById(R.id.grpc_server_state);
            if(newState == RpcServer.ServerState.RUNNING){
                grpcServerState.setText("GRPC Server State: RUNNING");
                startGRPCServerBtn.setEnabled(false);
                stopGRPCServerBtn.setEnabled(true);
            }else if(newState == RpcServer.ServerState.PENDING){
                grpcServerState.setText("GRPC Server State: PENDING");
                startGRPCServerBtn.setEnabled(false);
                stopGRPCServerBtn.setEnabled(false);
            }else{
                grpcServerState.setText("GRPC Server State: SHUTDOWN");
                startGRPCServerBtn.setEnabled(true);
                stopGRPCServerBtn.setEnabled(false);
            }
        });
    }

    private Void sendTask(Exception thrown){
        runOnUiThread(() ->{
            if(thrown != null){
                serverConnectStatus.append("Bundles upload failed.\n");
                Log.e(TAG, "Failed bundle upload, exception: "+thrown.getMessage());
            }else{
                serverConnectStatus.append("Bundles uploaded successfully.\n");
            }
        });

        return null;
    }

    private Void receiveTask(Exception thrown){
        runOnUiThread(() -> {
            if(thrown != null){
                serverConnectStatus.append("Bundles download failed.\n");
                Log.e(TAG, "Failed bundle d, exception: "+thrown.getMessage());
            }else{
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
        grpcServer = new RpcServer(this);

        domainInput = findViewById(R.id.domain_input);
        portInput = findViewById(R.id.port_input);

        // retrieve domain and port from shared preferences
        // populate text inputs if data is retrieved
        sharedPref = getSharedPreferences("server_endpoint", MODE_PRIVATE);
        restoreDomainPort();


        String SERVER_BASE_PATH = this.getExternalFilesDir(null) + "/BundleTransmission";
        Receive_Directory = SERVER_BASE_PATH +"/client";
        Server_Directory = SERVER_BASE_PATH +"/server";
        wifiDirectManager = new WifiDirectManager(this.getApplication(), this.getLifecycle());
        wifiDirectManager.initialize();

        // set up transport Id
        tidPath = getApplicationContext().getApplicationInfo().dataDir+"/transportIdentity.pub";
        File tid = new File(tidPath);
        if (!tid.exists()){
            ECKeyPair identityKeyPair = Curve.generateKeyPair();
            try {
                SecurityUtils.createEncodedPublicKeyFile(identityKeyPair.getPublicKey(), tidPath);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else{
            try {
                transportID = SecurityUtils.generateID(tidPath);
                Log.d(TAG, "Transport ID : "+ transportID);
            } catch (IOException | InvalidKeyException | NoSuchAlgorithmException e) {
                e.printStackTrace();
            }
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    MainActivity.PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION);
        }

        startGRPCServerBtn.setOnClickListener(v -> {
            executor.execute(() -> {
                synchronized (grpcServer){
                    if(grpcServer.isShutdown()){
                        startRpcWorkerService();
                        grpcServer.startServer(this, PORT);
                    }
                }
            });

            //startRpcWorkerService();
        });

        stopGRPCServerBtn.setOnClickListener(v -> {

            executor.execute(() -> {
                synchronized (grpcServer){
                    if(!grpcServer.isShutdown()){
                        grpcServer.shutdownServer();
                    }
                }
            });
            //stopRpcWorkerService();
        });

        findViewById(R.id.btn_clear_storage).setOnClickListener(v -> {
            FileUtils.deleteBundles(Receive_Directory);
            FileUtils.deleteBundles(Server_Directory);
        });


        // connect to server
        serverConnectStatus = findViewById(R.id.server_connection_status);
        Button connectServerBtn = findViewById(R.id.btn_connect_bundle_server);
        connectServerBtn.setOnClickListener(view -> {
            connectServerBtn.setEnabled(false);
            serverDomain = domainInput.getText().toString();
            serverPort = portInput.getText().toString();
            if(!serverDomain.isEmpty() && !serverPort.isEmpty()){
                Log.d(TAG, "Sending to "+serverDomain+":"+serverPort);

                Toast.makeText(MainActivity.this, "Sending to "+serverDomain+":"+serverPort, Toast.LENGTH_SHORT).show();

                serverConnectStatus.setText("Initiating server exchange...\n");

                // run async using multi threading
                executor.execute(new GrpcSendTask(this, serverDomain, serverPort, transportID, this::sendTask));
                executor.execute(new GrpcReceiveTask(this, serverDomain, serverPort, transportID, this::receiveTask));

            }else{
                Toast.makeText(MainActivity.this, "Enter the domain and port", Toast.LENGTH_SHORT).show();
            }
            connectServerBtn.setEnabled(true);
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

    private void restoreDomainPort(){
        domainInput.setText(sharedPref.getString("domain", ""));
        portInput.setText(sharedPref.getString("port", ""));
    }

    private void saveDomainPort(){
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putString("domain", domainInput.getText().toString());
        editor.putString("port", portInput.getText().toString());
        editor.apply();
    }

    @Override
    protected void onDestroy(){
        super.onDestroy();
        ExecutorService executor = Executors.newFixedThreadPool(1);

        executor.submit(() -> {
            synchronized (grpcServer){
                grpcServer.shutdownServer();
            }
        });
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e(TAG, "Stop rpc server interrupedexception: "+e.getMessage());
        }
        executor.shutdown();
    }
}