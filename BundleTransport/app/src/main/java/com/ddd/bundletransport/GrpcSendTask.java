package com.ddd.bundletransport;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.ddd.bundletransport.service.BundleMetaData;
import com.ddd.bundletransport.service.BundleServiceGrpc;
import com.ddd.bundletransport.service.BundleUploadObserver;
import com.ddd.bundletransport.service.BundleUploadRequest;
import com.google.protobuf.ByteString;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

public class GrpcSendTask {
    private final static String TAG = "dddTransport";
    private String host, serverDir, transportId;
    private int port;
    private ManagedChannel channel;


    public GrpcSendTask(String host, int port, String transportId, String serverDir) {
        Log.d(TAG, "initializing grpcsendtask...");
        this.host = host;
        this.port = port;
        this.transportId = transportId;
        this.serverDir = serverDir;
    }

    public Exception run(){
        Exception thrown = null;
        try {
            executeTask();
        } catch (Exception e) {
            thrown = e;
        }

        try {
            postExecuteTask();
        } catch (InterruptedException e) {
            Log.e(TAG, "Failed to shutdown GrpcSendTask channel: " + e.getMessage());
        }

        return thrown;
    }

    private void executeTask() throws IOException {
        Log.d(TAG, "executing grpcsendtask...");

        channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        BundleServiceGrpc.BundleServiceStub stub = BundleServiceGrpc.newStub(channel);
        StreamObserver<BundleUploadRequest> streamObserver = stub.uploadBundle(new BundleUploadObserver());
        File sendDir = new File(serverDir);
        Log.d(TAG, "received the stream observer: "+streamObserver);
        //get transport ID
        if (sendDir.exists()) {
            File[] bundles = sendDir.listFiles();
            if (bundles != null) {
                for (File bundle : bundles) {
                    BundleUploadRequest metadata = BundleUploadRequest
                            .newBuilder()
                            .setMetadata(BundleMetaData
                                    .newBuilder()
                                    .setBid(bundle.getName())
                                    .setTransportId(transportId)
                                    .build())
                            .build();
                    streamObserver.onNext(metadata);

                    // upload file as chunk
                    Log.d(TAG, "Started file transfer");
                    FileInputStream inputStream = new FileInputStream(bundle.getAbsolutePath());
                    ;
                    /*if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
                        inputStream = new FileInputStream(bundle.getAbsolutePath());
                    }

                     */
                    int chunkSize = 1000 * 1000 * 4;
                    byte[] bytes = new byte[chunkSize];
                    int size = 0;
                    while ((size = inputStream.read(bytes)) != -1) {
                        Log.d(TAG, "Sending chunk size: " + size);
                        BundleUploadRequest uploadRequest = BundleUploadRequest
                                .newBuilder()
                                .setFile(com.ddd.bundletransport.service.File
                                        .newBuilder()
                                        .setContent(ByteString
                                                .copyFrom(bytes, 0, size))
                                        .build())
                                .build();

                        streamObserver.onNext(uploadRequest);
                    }
                    inputStream.close();
                }
            }
        }

        Log.d(TAG, "Files are sent");
        streamObserver.onCompleted();
    }

    private void postExecuteTask() throws InterruptedException {
        channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
    }
}