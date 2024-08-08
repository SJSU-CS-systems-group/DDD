package net.discdd.bundleclient;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import android.app.Activity;
import android.content.Context;
import android.widget.TextView;

import net.discdd.bundlerouting.RoutingExceptions;
import net.discdd.bundlerouting.WindowUtils.WindowExceptions;
import net.discdd.client.bundletransmission.BundleTransmission;
import net.discdd.grpc.BundleDownloadRequest;
import net.discdd.grpc.BundleExchangeServiceGrpc;
import net.discdd.grpc.BundleSender;
import net.discdd.grpc.BundleSenderType;
import net.discdd.grpc.EncryptedBundleId;
import net.discdd.utils.Constants;

import org.whispersystems.libsignal.DuplicateMessageException;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.LegacyMessageException;
import org.whispersystems.libsignal.NoSessionException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
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

//  private class GrpcReceiveTask extends AsyncTask<String, Void, String> {
class BundleClientGrpcReceiveTask {

    private static final Logger logger = Logger.getLogger(BundleClientGrpcReceiveTask.class.getName());

    private Context applicationContext;
    private ManagedChannel channel;
    private final TextView resultText;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    BundleSender currentSender;
    private final Activity activity;
    private BundleTransmission bundleTransmission;

    BundleClientGrpcReceiveTask(Activity activity) {
        this.resultText = (TextView) activity.findViewById(R.id.grpc_response_text);
        this.applicationContext = activity.getApplicationContext();
        this.activity = activity;
        this.bundleTransmission = ((BundleClientActivity)activity).bundleTransmission;
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
        Path FILE_PATH = Paths.get(applicationContext.getApplicationInfo().dataDir, "Shared/received-bundles");
        FILE_PATH.toFile().mkdirs();
        channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        var stub = BundleExchangeServiceGrpc.newBlockingStub(channel);
        List<String> bundleRequests = null;
        logger.log(FINE, "Starting File Receive");
        activity.runOnUiThread(() -> resultText.append(applicationContext.getString(R.string.starting_file_receive)));
        String clientId = null;
        try {
            bundleRequests = BundleClientActivity.clientWindow.getWindow(
                    bundleTransmission.getBundleSecurity().getClientSecurity());
            clientId = bundleTransmission.getBundleSecurity().getClientSecurity().getClientID();
        } catch (RuntimeException e) {
            logger.log(WARNING, "{BR}: Failed to get Window: " + e);
            e.printStackTrace();
        } catch (Exception e) {
            logger.log(WARNING, "{BR}: Failed to get Window: " + e);
            e.printStackTrace();
        }
        if (bundleRequests == null) {
            logger.log(FINE, "BUNDLE REQuests is NUll / ");

            postExecute(applicationContext.getString(R.string.incomplete_bundle_request), host, String.valueOf(port));
        } else if (bundleRequests.size() == 0) {
            logger.log(FINE, "BUNDLE REQuests has size 0 / ");
        }

        var sender = BundleSender.newBuilder().setId(clientId).setType(BundleSenderType.CLIENT).build();
        var successful = false;

        for (String bundle : bundleRequests) {
            var downloadRequest = BundleDownloadRequest.newBuilder()
                    .setSender(sender)
                    .setBundleId(EncryptedBundleId.newBuilder().setEncryptedId(bundle).build())
                    .build();

            String bundleName = bundle + ".bundle";
            logger.log(INFO, "Downloading file: " + bundleName);
            var responses =
                    stub.withDeadlineAfter(Constants.GRPC_LONG_TIMEOUT_MS, TimeUnit.MILLISECONDS).downloadBundle(downloadRequest);

            try {
                final OutputStream fileOutputStream = responses.hasNext() ?
                        Files.newOutputStream(FILE_PATH.resolve(bundleName), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING) : null;

                while (responses.hasNext()) {
                    var response = responses.next();
                    fileOutputStream.write(response.getChunk().getChunk().toByteArray());
                }
                successful = true;
                break;
            } catch (StatusRuntimeException e) {
                logger.log(SEVERE, "Receive bundle failed " + channel, e);
            }
        }
        postExecute(successful ? applicationContext.getString(R.string.completed) :
                            applicationContext.getString(R.string.failed), host, String.valueOf(port));
    }

    public void shutdownExecutor() {
        executor.shutdown();
    }

    protected void postExecute(String result, String host, String port) throws NoSessionException,
            InvalidMessageException, WindowExceptions.BufferOverflow, DuplicateMessageException,
            RoutingExceptions.ClientMetaDataFileException, IOException, LegacyMessageException, InvalidKeyException, GeneralSecurityException {
        activity.runOnUiThread(() -> resultText.append(String.format("Receive finished: %s\n", result)));
        if (result.equals("Incomplete")) {
            return;
        }
        try {
            channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        BundleClientGrpcSendTask sendTask = new BundleClientGrpcSendTask(activity);
        try {
            // we are already in the background, so just execute it
            sendTask.inBackground(host, port);
        } catch (Exception e) {
            logger.log(SEVERE, "Problem sending bundle", e);
        }

        String FILE_PATH = applicationContext.getApplicationInfo().dataDir + "/Shared/received-bundles";
        bundleTransmission.processReceivedBundles(currentSender, FILE_PATH);

    }
}
