package net.discdd.bundleclient;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import android.app.Activity;
import android.content.Context;
import android.widget.TextView;

import net.discdd.utils.Constants;
import net.discdd.bundlerouting.RoutingExceptions;
import net.discdd.bundlerouting.WindowUtils.WindowExceptions;
import net.discdd.client.bundletransmission.BundleTransmission;

import net.discdd.bundletransport.service.Bytes;
import net.discdd.bundletransport.service.FileServiceGrpc;
import net.discdd.bundletransport.service.ReqFilePath;

import org.whispersystems.libsignal.DuplicateMessageException;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.LegacyMessageException;
import org.whispersystems.libsignal.NoSessionException;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

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
                    logger.log(WARNING, "executeInBackground failed", e);
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
        var stub = FileServiceGrpc.newBlockingStub(channel);
        List<String> bundleRequests = null;
        logger.log(FINE, "Starting File Receive");
        activity.runOnUiThread(() -> resultText.append("Starting File Receive...\n"));
        try {
            var bundleTransmission = new BundleTransmission(Paths.get(applicationContext.getApplicationInfo().dataDir));
            bundleRequests = BundleClientActivity.clientWindow.getWindow(
                    bundleTransmission.getBundleSecurity().getClientSecurity());
        } catch (RuntimeException e) {
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

        final var errorOccurred = new boolean[1];

        for (String bundle : bundleRequests) {
            String bundleName = bundle + ".bundle";
            ReqFilePath request = ReqFilePath.newBuilder().setValue(bundleName).build();
            logger.log(INFO, "Downloading file: " + bundleName);
            var downloadObserver = new DownloadObserver(FILE_PATH, bundleName);

            var responses =
                    stub.withDeadlineAfter(Constants.GRPC_LONG_TIMEOUT_MS, TimeUnit.MILLISECONDS).downloadFile(request);

            try {
                final FileOutputStream fileOutputStream =
                        responses.hasNext() ? new FileOutputStream(FILE_PATH + "/" + bundleName) : null;

                responses.forEachRemaining(r -> {
                    try {
                        fileOutputStream.write(r.getValue().toByteArray());
                        currentTransportId = r.getTransportId();
                    } catch (IOException e) {
                        errorOccurred[0] = true;
                        logger.log(SEVERE, "Cannot write bytes ", e);
                    }
                });
            } catch (StatusRuntimeException e) {
                logger.log(SEVERE, "Receive bundle failed " + channel, e);
            }

            break;
        }
        postExecute(errorOccurred[0] ? "Failed" : "Completed");
    }

    public void shutdownExecutor() {
        executor.shutdown();
    }

    //    @Override
//    protected String doInBackground(String... params) {
//      //code has been moved
//    }
    protected void postExecute(String result) throws NoSessionException, InvalidMessageException,
            WindowExceptions.BufferOverflow, DuplicateMessageException, RoutingExceptions.ClientMetaDataFileException
            , IOException, LegacyMessageException, InvalidKeyException, WindowExceptions.InvalidLength,
            GeneralSecurityException {
        if (result.equals("Incomplete")) {
            activity.runOnUiThread(() -> resultText.append(result + "\n"));
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
        sendTask.executeInBackground("192.168.49.1", "7777");

        String FILE_PATH = applicationContext.getApplicationInfo().dataDir + "/Shared/received-bundles";
        BundleTransmission bundleTransmission =
                new BundleTransmission(Paths.get(applicationContext.getApplicationInfo().dataDir));
        bundleTransmission.processReceivedBundles(currentTransportId, FILE_PATH);

        Activity activity = activityReference.get();
        if (activity == null) {
            return;
        }
    }

    private class DownloadObserver implements StreamObserver<Bytes> {
        private final String FILE_PATH;
        private final String bundleName;
        FileOutputStream fileOutputStream;

        public boolean errorOccurred;
        public boolean complete;

        public DownloadObserver(String FILE_PATH, String bundleName) {
            this.FILE_PATH = FILE_PATH;
            this.bundleName = bundleName;
        }

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
            errorOccurred = true;
            logger.log(SEVERE, "Error downloading file: ", t);
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
            complete = true;

            try {
                fileOutputStream.flush();
                fileOutputStream.close();
            } catch (IOException e) {
                logger.log(SEVERE, "Error closing output stream", e);
            }

            logger.log(INFO, "File download complete");
        }

    }
}
