package com.ddd.bundleclient;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.widget.TextView;

import com.ddd.client.bundlesecurity.SecurityExceptions;
import com.ddd.client.bundletransmission.BundleTransmission;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.util.logging.Logger;
import static java.util.logging.Level.FINER;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Level.SEVERE;

//  private class GrpcReceiveTask extends AsyncTask<String, Void, String> {
class GrpcReceiveTask {

    private static final Logger logger = Logger.getLogger(GrpcReceiveTask.class.getName());

    private Context applicationContext;
    private final WeakReference<Activity> activityReference;
    private ManagedChannel channel;
    private final TextView resultText;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    String currentTransportId;
    private final Activity activity;

    GrpcReceiveTask(Activity activity) {
        this.activityReference = new WeakReference<Activity>(activity);
        this.resultText = (TextView) activity.findViewById(R.id.grpc_response_text);
        this.applicationContext = activity.getApplicationContext();
        this.activity = activity;
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
        String host = params[0];
        String portStr = params[1];
        String FILE_PATH = applicationContext.getApplicationInfo().dataDir + "/Shared/received-bundles";
        java.io.File file = new java.io.File(FILE_PATH);
        file.mkdirs();
        int port = Integer.parseInt(portStr);
        channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        FileServiceGrpc.FileServiceStub stub = FileServiceGrpc.newStub(channel);
        List<String> bundleRequests = null;
        logger.log(FINE, "Starting File Receive");
        resultText.append("Starting File Receive...\n");
        try {
            BundleTransmission bundleTransmission;
            bundleTransmission = new BundleTransmission(applicationContext.getApplicationInfo().dataDir);
            bundleRequests = HelloworldActivity.clientWindow.getWindow(
                    bundleTransmission.getBundleSecurity().getClientSecurity());
        } catch (SecurityExceptions.BundleIDCryptographyException e) {
            logger.log(WARNING, "{BR}: Failed to get Window: " + e);
            e.printStackTrace();
        } catch (Exception e) {
            logger.log(WARNING, "{BR}: Failed to get Window: " + e);
            e.printStackTrace();
        }
        if (bundleRequests == null) {
            logger.log(FINE, "BUNDLE REQuests is NUll / ");
///        throw new Exception("bundle request is null");
            postExecute("Incomplete");
        } else if (bundleRequests.size() == 0) {
            logger.log(FINE, "BUNDLE REQuests has size 0 / ");
        }
        for (String bundle : bundleRequests) {
            String bundleName = bundle + ".bundle";
            ReqFilePath request = ReqFilePath.newBuilder().setValue(bundleName).build();
            logger.log(INFO, "Downloading file: " + bundleName);
            StreamObserver<Bytes> downloadObserver = new StreamObserver<Bytes>() {
                FileOutputStream fileOutputStream = null;

                @Override
                public void onNext(Bytes fileContent) {
                    try {
                        if (fileOutputStream == null) {
                            fileOutputStream = new FileOutputStream(FILE_PATH + "/" + bundleName);
                        }
                        fileOutputStream.write(fileContent.getValue().toByteArray());
                        currentTransportId = fileContent.getTransportId();
                    } catch (IOException e) {
                        onError(e);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    logger.log(SEVERE, "Error downloading file: " + t.getMessage(), t);
                    if (fileOutputStream != null) {
                        try {
                            fileOutputStream.close();
                        } catch (IOException e) {
                            logger.log(SEVERE, "Error closing output stream", e);
                        }
                    }
                }

                @Override
                public void onCompleted() {
                    try {
                        fileOutputStream.flush();
                        fileOutputStream.close();
                    } catch (IOException e) {
                        logger.log(SEVERE, "Error closing output stream", e);
                    }
                    logger.log(INFO, "File download complete");
                }
            };
            stub.downloadFile(request, downloadObserver);
            break;
        }
        postExecute("Complete");
    }

    public void shutdownExecutor() {
        executor.shutdown();
    }

    //    @Override
//    protected String doInBackground(String... params) {
//      //code has been moved
//    }
    protected void postExecute(String result) {
        if (result.equals("Incomplete")) {
            resultText.append(result + "\n");
            return;
        }
        try {
            channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
//        new HelloworldActivity.GrpcSendTask(HelloworldActivity.this)
//        new GrpcSendTask.executeInBackground("192.168.49.1", "1778");
        GrpcSendTask sendTask = new GrpcSendTask(activity);
        sendTask.executeInBackground("192.168.49.1", "1778");

        String FILE_PATH = applicationContext.getApplicationInfo().dataDir + "/Shared/received-bundles";
        BundleTransmission bundleTransmission = new BundleTransmission(applicationContext.getApplicationInfo().dataDir);
        bundleTransmission.processReceivedBundles(currentTransportId, FILE_PATH);

        Activity activity = activityReference.get();
        if (activity == null) {
            return;
        }
    }
}
