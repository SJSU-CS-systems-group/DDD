package com.ddd.bundleclient;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import com.ddd.client.bundletransmission.BundleTransmission;
import com.ddd.model.BundleDTO;
import com.google.protobuf.ByteString;

import java.io.FileInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

//Venus added
import java.util.logging.Logger;
import static java.util.logging.Level.FINER;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Level.SEVERE;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

class GrpcSendTask {
    //    private final HelloworldActivity helloworldActivity;
    private final WeakReference<Activity> activityReference;
    private ManagedChannel channel;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Context applicationContext;

    GrpcSendTask(Activity activity) {
//        this.helloworldActivity = helloworldActivity;
        this.activityReference = new WeakReference<Activity>(activity);
        this.applicationContext = activity.getApplicationContext();
    }

    public void executeInBackground(String port, String host) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    inBackground(port, host);
                } catch (Exception e) {
                    // Handle any exceptions
                    logger.log(WARNING, "executeInBackground failed");
                }
            }
        });
    }

    private void inBackground(String... params) throws Exception {
//    protected String doInBackground(String... params) {
        String host = params[0];
        String portStr = params[1];
        int port = Integer.parseInt(portStr);
        try {
            channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
            FileServiceGrpc.FileServiceStub stub = FileServiceGrpc.newStub(channel);
            StreamObserver<FileUploadRequest> streamObserver = stub.uploadFile(new FileUploadObserver());
            BundleTransmission bundleTransmission;
            bundleTransmission = new BundleTransmission(applicationContext.getApplicationInfo().dataDir);
            BundleDTO toSend = bundleTransmission.generateBundleForTransmission();
            System.out.println("[BDA] An outbound bundle generated with id: " + toSend.getBundleId());
            FileUploadRequest metadata = FileUploadRequest.newBuilder()
                    .setMetadata(MetaData.newBuilder().setName(toSend.getBundleId()).setType("bundle").build()).build();
            streamObserver.onNext(metadata);

//      upload file as chunk
            Log.d(HelloworldActivity.TAG, "Started file transfer");
            FileInputStream inputStream = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                inputStream = new FileInputStream(toSend.getBundle().getSource());
            }
            int chunkSize = 1000 * 1000 * 4;
            byte[] bytes = new byte[chunkSize];
            int size = 0;
            while ((size = inputStream.read(bytes)) != -1) {
                FileUploadRequest uploadRequest = FileUploadRequest.newBuilder()
                        .setFile(File.newBuilder().setContent(ByteString.copyFrom(bytes, 0, size)).build()).build();
                streamObserver.onNext(uploadRequest);
            }
            inputStream.close();
            streamObserver.onCompleted();
            logger.log(INFO, "Completed file transfer");
            postExecute("Complete");
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            pw.flush();
//        return String.format("Failed... : %n%s", sw);
            postExecute("Failed... : " + sw);
        }
    }

    protected void postExecute(String result) {
        try {
            channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Activity activity = activityReference.get();
        if (activity == null) {
            return;
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView resultText = activity.findViewById(R.id.grpc_response_text);
                Button exchangeButton = activity.findViewById(R.id.exchange_button);
                resultText.setText(result);
                exchangeButton.setEnabled(true);
            }
        });
        //call UI thread
    }
}
