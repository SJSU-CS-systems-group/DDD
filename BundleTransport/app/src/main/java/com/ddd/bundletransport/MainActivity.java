package com.ddd.bundletransport;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.ddd.wifidirect.WifiDirectManager;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final int PORT = 7777;
    public static final int PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION = 1001;
    public static final String TAG = "dddDebug";
    private WifiDirectManager wifiDirectManager;


    /**
     * check for location permissions manually, will give a prompt*/
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION:
                if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "Fine location permission is not granted!");
                    finish();
                }
                break;
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

        Data data = new Data.Builder().putInt("PORT", PORT).build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                RpcServerWorker.class,
                15, TimeUnit.MINUTES,
                15, TimeUnit.MINUTES)
                .setInputData(data)
                .build();
        WorkManager.getInstance(this)
                .enqueueUniquePeriodicWork(TAG,
                        ExistingPeriodicWorkPolicy.REPLACE, request);

        Toast.makeText(this, "Start Rpc Server", Toast.LENGTH_SHORT).show();
    }

    private void stopRpcWorkerService() {
        WorkManager.getInstance(this).cancelUniqueWork(TAG);

        Toast.makeText(this, "Stop Rpc Server", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        wifiDirectManager = new WifiDirectManager(this.getApplication(), this.getLifecycle());
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    MainActivity.PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION);
        }

        findViewById(R.id.btn_start_rpc_server).setOnClickListener(v -> {
            startRpcWorkerService();
        });

        findViewById(R.id.btn_stop_rpc_server).setOnClickListener(v -> {
            stopRpcWorkerService();
        });
    }
}