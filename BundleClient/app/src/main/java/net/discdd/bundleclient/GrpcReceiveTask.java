package net.discdd.bundleclient;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import android.app.Activity;
import android.content.Context;
import android.widget.TextView;

import net.discdd.bundlerouting.BundleSender;
import net.discdd.bundlerouting.RoutingExceptions;
import net.discdd.bundlerouting.WindowUtils.WindowExceptions;
import net.discdd.bundletransport.service.Bytes;
import net.discdd.bundletransport.service.FileServiceGrpc;
import net.discdd.bundletransport.service.ReqFilePath;
import net.discdd.client.bundletransmission.BundleTransmission;
import net.discdd.utils.Constants;

import org.whispersystems.libsignal.DuplicateMessageException;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.LegacyMessageException;
import org.whispersystems.libsignal.NoSessionException;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
    private ManagedChannel channel;
    private final TextView resultText;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    String currentSenderId;
    BundleSender currentSender;
    private final Activity activity;

    GrpcReceiveTask(Activity activity) {
        this.resultText = (TextView) activity.findViewById(R.id.grpc_response_text);
        this.applicationContext = activity.getApplicationContext();
        this.activity = activity;
    }

    public CompletableFuture<Boolean> executeInBackground(String domain, int port) {
        final CompletableFuture<Boolean> completableFuture = new CompletableFuture<>();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    logger.log(INFO, "Inside GrpcReceiveTask executeInBackground.run");
                    InetSocketAddress address = new InetSocketAddress(domain, port);
                    inBackground(address);
                    completableFuture.complete(true);
                } catch (Exception e) {
                    // Handle any exceptions
                    logger.log(WARNING, "executeInBackground failed", e);
                    completableFuture.complete(false);
                }
            }
        });
        return completableFuture;
    }

    private void inBackground(InetSocketAddress inetSocketAddress) throws Exception {
        String host = inetSocketAddress.getHostName();
        int port = inetSocketAddress.getPort();
        logger.log(INFO, "Inside GrpcReceiveTask inBackground");
        String FILE_PATH = applicationContext.getApplicationInfo().dataDir + "/Shared/received-bundles";
        java.io.File file = new java.io.File(FILE_PATH);
        file.mkdirs();
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

            postExecute("Incomplete", host, String.valueOf(port));
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
                        currentSenderId = r.getSenderId();
                        r.getSender();
                        if (!r.getSender().isBlank()) currentSender = BundleSender.valueOf(r.getSender());
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
        postExecute(errorOccurred[0] ? "Failed" : "Completed", host, String.valueOf(port));
    }

    public void shutdownExecutor() {
        executor.shutdown();
    }

    protected void postExecute(String result, String host, String port) throws NoSessionException,
            InvalidMessageException, WindowExceptions.BufferOverflow, DuplicateMessageException,
            RoutingExceptions.ClientMetaDataFileException, IOException, LegacyMessageException, InvalidKeyException,
            WindowExceptions.InvalidLength, GeneralSecurityException {
        activity.runOnUiThread(() -> resultText.append(String.format("Receive finished: %s\n", result)));
        if (result.equals("Incomplete")) {
            return;
        }
        try {
            channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        GrpcSendTask sendTask = new GrpcSendTask(activity);
        try {
            // we are already in the background, so just execute it
            sendTask.inBackground(host, port);
        } catch (Exception e) {
            logger.log(SEVERE, "Problem sending bundle", e);
        }

        String FILE_PATH = applicationContext.getApplicationInfo().dataDir + "/Shared/received-bundles";
        BundleTransmission bundleTransmission =
                new BundleTransmission(Paths.get(applicationContext.getApplicationInfo().dataDir));
        bundleTransmission.processReceivedBundles(currentSenderId, currentSender, FILE_PATH);

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
                currentSenderId = fileContent.getSenderId();
                if (!fileContent.getSender().isBlank()) currentSender = BundleSender.valueOf(fileContent.getSender());
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
