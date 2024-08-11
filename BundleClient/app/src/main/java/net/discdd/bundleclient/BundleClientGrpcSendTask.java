package net.discdd.bundleclient;

import static java.util.logging.Level.INFO;

import android.app.Activity;
import android.content.Context;
import android.widget.TextView;

import com.google.protobuf.ByteString;

import net.discdd.bundlerouting.service.BundleUploadResponseObserver;
import net.discdd.client.bundletransmission.BundleTransmission;
import net.discdd.grpc.BundleChunk;
import net.discdd.grpc.BundleExchangeServiceGrpc;
import net.discdd.grpc.BundleUploadRequest;
import net.discdd.grpc.EncryptedBundleId;
import net.discdd.model.BundleDTO;
import net.discdd.utils.Constants;

import java.io.FileInputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

class BundleClientGrpcSendTask {

    private static final Logger logger = Logger.getLogger(BundleClientGrpcSendTask.class.getName());

    private final WeakReference<Activity> activityReference;
    private ManagedChannel channel;
    private final Context applicationContext;
    private final BundleTransmission bundleTransmission;

    BundleClientGrpcSendTask(Activity activity) {
        this.activityReference = new WeakReference<>(activity);
        this.applicationContext = activity.getApplicationContext();
        this.bundleTransmission = ((BundleClientActivity) activity).bundleTransmission;
    }

    void inBackground(String... params) {
        String host = params[0];
        String portStr = params[1];
        int port = Integer.parseInt(portStr);
        try {
            BundleDTO toSend = bundleTransmission.generateBundleForTransmission();

            channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
            var stub = BundleExchangeServiceGrpc.newStub(channel);
            var bundleUploadResponseObserver = new BundleUploadResponseObserver();
            StreamObserver<BundleUploadRequest> uploadRequestStreamObserver =
                    stub.withDeadlineAfter(Constants.GRPC_SHORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .uploadBundle(bundleUploadResponseObserver);

            uploadRequestStreamObserver.onNext(BundleUploadRequest.newBuilder().setBundleId(
                    EncryptedBundleId.newBuilder().setEncryptedId(toSend.getBundleId()).build()).build());

            // upload file as chunk
            logger.log(INFO, "Started file transfer");
            try (FileInputStream inputStream = new FileInputStream(toSend.getBundle().getSource())) {
                int chunkSize = 1000 * 1000 * 4;
                byte[] bytes = new byte[chunkSize];
                int size;
                while ((size = inputStream.read(bytes)) != -1) {
                    var uploadRequest = BundleUploadRequest.newBuilder()
                            .setChunk(BundleChunk.newBuilder().setChunk(ByteString.copyFrom(bytes, 0, size)).build())
                            .build();
                    uploadRequestStreamObserver.onNext(uploadRequest);
                }
            }
            uploadRequestStreamObserver.onCompleted();
            logger.log(INFO, "Completed file transfer");
            postExecute(applicationContext.getString(R.string.complete));
        } catch (Exception e) {
            postExecute(String.format("Failed to send Bundle: %s", e.getMessage()));
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
        activity.runOnUiThread(() -> {
            TextView resultText = activity.findViewById(R.id.grpc_response_text);
            resultText.append(String.format("Send finished: %s\n", result));
        });
        //call UI thread
    }
}
