package com.ddd.bundletransport;

import android.content.Context;
import android.util.Log;

import com.ddd.bundletransport.service.BundleDownloadRequest;
import com.ddd.bundletransport.service.BundleDownloadResponse;
import com.ddd.bundletransport.service.BundleServiceGrpc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

public class GrpcReceiveTask {
    private final static String TAG = "dddTransport";
    private String host, receiveDir, transportId;
    private int port;
    private boolean receiveBundles, statusComplete;
    private ManagedChannel channel;

    public GrpcReceiveTask(String host, int port, String transportId, String receiveDir) {
        Log.d(TAG, "initializing grpcreceivetask...");
        this.host = host;
        this.port = port;
        this.transportId = transportId;
        this.receiveDir = receiveDir;
    }

    public Exception run() {
        Exception thrown = null;
        try {
            executeTask();
            Log.d(TAG, "Executed receive task");
        } catch (Exception e) {
            thrown = e;
        }

        try {
            postExecuteTask();
        } catch (InterruptedException e) {
            Log.e(TAG, "Failed to shutdown GrpcReceiveTask channel: " + e.getMessage());
        }

        return thrown;
    }

    private void executeTask() throws Exception {
        Log.d(TAG, "executing grpcreceivetask...");

        channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        BundleServiceGrpc.BundleServiceStub stub = BundleServiceGrpc.newStub(channel);
        // using an array because it is set in the anonymous class below
        Throwable[] thrown = new Throwable[1];
        receiveBundles = true;
        statusComplete = true;

        StreamObserver<BundleDownloadResponse> downloadObserver = new StreamObserver<BundleDownloadResponse>() {
            FileOutputStream fileOutputStream = null;
            OutputStream writer;

            @Override
            public void onNext(BundleDownloadResponse response) {
                Log.d(TAG, "onNext: called with " + response.toString());
                if (response.hasBundleList()) {
                    Log.d(TAG, "Got list for deletion");
                    List<String> toDelete = Arrays.asList(response.getBundleList().getBundleList().split(","));
                    if (!toDelete.isEmpty()) {
                        File clientDir = new File(receiveDir);
                        for (File bundle : clientDir.listFiles()) {
                            if (toDelete.contains(bundle.getName())) {
                                Log.d(TAG, "Deleteing file: " + bundle.getName());
                                bundle.delete();
                            }
                        }
                    } else {
                        Log.d(TAG, "No bundles to delete");
                    }
                } else if (response.hasStatus()) {
                    Log.d(TAG, "Status found: terminating loop");
                    receiveBundles = false;
                } else if (response.hasMetadata()) {
                    try {
                        Log.d(TAG, "Downloading chunk of: " + response.getMetadata().getBid());
                        writer = FileUtils.getFilePath(response, receiveDir);
                    } catch (IOException e) {
                        Log.e(TAG, "/GrpcReceiveTask.java -> executeTask() -> onNext() IOException: " + e.getMessage());
                    }
                } else {
                    try {
                        FileUtils.writeFile(writer, response.getFile().getContent());
                    } catch (IOException e) {
                        Log.e(TAG, "/GrpcReceiveTask.java -> executeTask() -> onNext() IOException: " + e.getMessage());
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                Log.e(TAG, "Error downloading file: " + t.getMessage(), t);
                receiveBundles = false;
                thrown[0] = t;
                if (fileOutputStream != null) {
                    try {
                        fileOutputStream.close();
                    } catch (IOException e) {
                        Log.e(TAG,
                              "/GrpcReceiveTask.java -> executeTask() -> onError() IOException: " + e.getMessage());

                    }
                }
            }

            @Override
            public void onCompleted() {
                if (writer != null) {
                    FileUtils.closeFile(writer);
                }

                Log.d(TAG, "File download complete");
                statusComplete = true;
            }
        };

        while (receiveBundles) {
            if (statusComplete) {
                Log.d(TAG, "/GrpcReceiveTask.java -> executeTask() receiveBundles = " + receiveBundles);

                String existingBundles = FileUtils.getFilesList(receiveDir);
                BundleDownloadRequest request =
                        BundleDownloadRequest.newBuilder().setTransportId(transportId).setBundleList(existingBundles)
                                .build();

                stub.downloadBundle(request, downloadObserver);

                Log.d(TAG, "Receive task complete");
                statusComplete = false;
            }
        }
        Log.d(TAG, "Error thrown: " + thrown[0]);
        if (thrown[0] != null) {
            throw new Exception(thrown[0].getMessage());
        }
    }

    private void postExecuteTask() throws InterruptedException {
        channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
    }
}
