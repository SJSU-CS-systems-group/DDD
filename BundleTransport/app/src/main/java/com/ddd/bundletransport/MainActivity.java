package com.ddd.bundletransport;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.ddd.bundletransport.service.BundleDownloadRequest;
import com.ddd.bundletransport.service.BundleDownloadResponse;
import com.ddd.bundletransport.service.BundleMetaData;
import com.ddd.bundletransport.service.BundleServiceGrpc;
import com.ddd.bundletransport.service.BundleUploadObserver;
import com.ddd.bundletransport.service.BundleUploadRequest;
import com.ddd.wifidirect.WifiDirectManager;
import com.google.protobuf.ByteString;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

public class MainActivity extends AppCompatActivity {

    private static final int PORT = 7777;
    public static final int PERMISSIONS_REQUEST_CODE_ACCESS_FINE_LOCATION = 1001;
    public static final String TAG = "dddTransport";
    private WifiDirectManager wifiDirectManager;
    private String Receive_Directory;
    private String Server_Directory;

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

    private void deleteBundles(String directory){
        File deleteDir = new File(directory);
        if (deleteDir.listFiles() != null) {
            for (File bundle : Objects.requireNonNull(deleteDir.listFiles())){
                boolean result = bundle.delete();
                Log.d(TAG, bundle.getName()+"deleted:"+ result);
            }
        }
    }


    private class GrpcSendTask extends AsyncTask<String, Void, String> {
        private final WeakReference<Activity> activityReference;
        private ManagedChannel channel;

        private GrpcSendTask(Activity activity) {
            this.activityReference = new WeakReference<Activity>(activity);
        }

        @Override
        protected String doInBackground(String... params) {
            String host = params[0];
            String portStr = params[1];
            int port =Integer.parseInt(portStr);
            try {
                channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
                BundleServiceGrpc.BundleServiceStub stub = BundleServiceGrpc.newStub(channel);
                StreamObserver<BundleUploadRequest> streamObserver = stub.uploadBundle(new BundleUploadObserver());
                File send_dir = new File(Server_Directory);
                //get transport ID
                if(send_dir.exists()){
                    File[] bundles = send_dir.listFiles();
                    if (bundles != null){
                        for(File bundle: bundles){
                            BundleUploadRequest metadata = BundleUploadRequest.newBuilder()
                                    .setMetadata(BundleMetaData.newBuilder()
                                            .setBid(bundle.getName())
                                            .setTransportId("test_1")
                                            .build())
                                    .build();
                            streamObserver.onNext(metadata);

                            //                upload file as chunk
                            Log.d(TAG,"Started file transfer");
                            FileInputStream inputStream = null;
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                inputStream = new FileInputStream(bundle.getAbsolutePath());
                            }
                            int chunkSize = 1000*1000*4;
                            byte[] bytes = new byte[chunkSize];
                            int size = 0;
                            while ((size = inputStream.read(bytes)) != -1){
                                Log.d(TAG,"Sending chunk"+size);
                                BundleUploadRequest uploadRequest = BundleUploadRequest.newBuilder()
                                        .setFile(com.ddd.bundletransport.service.File.newBuilder().setContent(ByteString.copyFrom(bytes, 0 , size)).build())
                                        .build();
                                streamObserver.onNext(uploadRequest);
                            }
                            inputStream.close();
                            bundle.delete();
                        }
                    }
                }
                // close the stream
                Log.d(TAG,"Complete");
                streamObserver.onCompleted();
                return "Complete";
            } catch (Exception e) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);
                pw.flush();
                Log.d(TAG,String.valueOf(sw));
                return String.format("Failed... : %n%s", sw);
            }
        }

        @Override
        protected void onPostExecute(String result) {
            try {
                channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Activity activity = activityReference.get();
            if (activity == null) {
                return;
            }
//            TextView resultText = (TextView) activity.findViewById(R.id.grpc_response_text);
//            resultText.setText(result);
            new GrpcReceiveTask(MainActivity.this)
                    .execute(
                            "10.0.0.166",
                            "8080");
        }
    }

    private class GrpcReceiveTask extends AsyncTask<String, Void, String> {
        private final WeakReference<Activity> activityReference;
        private ManagedChannel channel;
        boolean receiveBundles;
        boolean statusComplete;

        private GrpcReceiveTask(Activity activity) {
            this.activityReference = new WeakReference<Activity>(activity);
        }

        @Override
        protected String doInBackground(String... params) {
            String host = params[0];
            String portStr = params[1];
            int port = Integer.parseInt(portStr);
            channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
            BundleServiceGrpc.BundleServiceStub stub = BundleServiceGrpc.newStub(channel);

            receiveBundles = true;
            statusComplete = true;
            StreamObserver<BundleDownloadResponse> downloadObserver = new StreamObserver<BundleDownloadResponse>() {
                FileOutputStream fileOutputStream = null;
                OutputStream writer;

                @Override
                public void onNext(BundleDownloadResponse response) {
                    Log.d(TAG, "onNext: called with "+ response.toString());
                    if (response.hasBundleList()){
                        Log.d(TAG, "Got List for deletion");
                        List<String> toDelete = Arrays.asList(response.getBundleList().getBundleList().split(","));
                        if ( !toDelete.isEmpty() ){
                            File clientDir = new File(Receive_Directory);
                            for (File bundle : clientDir.listFiles()){
                                if (toDelete.contains(bundle.getName())){
                                    Log.d(TAG, "Deleting file"+bundle.getName());
                                    bundle.delete();
                                }
                            }
                        } else {
                            Log.d(TAG, "No Bundle to delete");
                        }
                    } else if (response.hasStatus()){
                        Log.d(TAG, "Status found: terminating loop");
                        receiveBundles = false;
                    } else if(response.hasMetadata()){
                        try {
                            Log.d(TAG,"Downloading chunk of :"+response.getMetadata().getBid());
                            writer = fileUtils.getFilePath(response, Receive_Directory);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    } else{
                        try {
                            fileUtils.writeFile(writer, response.getFile().getContent());
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }

                }

                @Override
                public void onError(Throwable t) {
                    Log.d(TAG, "Error downloading file: " + t.getMessage(), t);
                    if (fileOutputStream != null) {
                        try {
                            fileOutputStream.close();
                        } catch (IOException e) {
                            Log.d(TAG, "Error closing output stream", e);
                        }
                    }
                }

                @Override
                public void onCompleted() {
                    fileUtils.closeFile(writer);
                    Log.d(TAG, "File download complete");
                    statusComplete = true;
                }
            };

            while  (receiveBundles){
                if(statusComplete){
                    Log.d(TAG, "doInBackground: receiveBundles" + receiveBundles);
                    String existingBundles = fileUtils.getFilesList(Receive_Directory);
                    BundleDownloadRequest request = BundleDownloadRequest.newBuilder()
                            .setTransportId("test_1")
                            .setBundleList(existingBundles)
                            .build();
                    stub.downloadBundle(request, downloadObserver);
                    statusComplete = false;
                }
            }
            return "Complete";
        }

        @Override
        protected void onPostExecute(String result) {
            try {
                channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Activity activity = activityReference.get();
            if (activity == null) {
                return;
            }
//            TextView resultText = (TextView) activity.findViewById(R.id.grpc_response_text);
//            resultText.setText(result);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Button sendBundleServerButton = findViewById(R.id.btn_connect_bundle_server);
        String SERVER_BASE_PATH = this.getExternalFilesDir(null) + "/BundleTransmission";
        Receive_Directory = SERVER_BASE_PATH +"/client";
        Server_Directory = SERVER_BASE_PATH +"/server";
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

        findViewById(R.id.btn_clear_storage).setOnClickListener(v -> {
            deleteBundles(Receive_Directory);
            deleteBundles(Server_Directory);
        });

        sendBundleServerButton.setOnClickListener(view -> {
//                send task
            new GrpcSendTask(MainActivity.this)
                    .execute(
                            "10.0.0.166",
                            "8080");
        });
    }
}