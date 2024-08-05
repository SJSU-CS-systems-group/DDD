package net.discdd.bundleclient;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

import android.app.Activity;
import android.content.Context;
import android.widget.TextView;

import com.google.protobuf.ByteString;

import net.discdd.bundletransport.service.File;
import net.discdd.bundletransport.service.FileServiceGrpc;
import net.discdd.bundletransport.service.FileUploadRequest;
import net.discdd.bundletransport.service.MetaData;
import net.discdd.client.bundletransmission.BundleTransmission;
import net.discdd.model.BundleDTO;
import net.discdd.utils.Constants;

import java.io.FileInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

class GrpcSendTask {

    private static final Logger logger = Logger.getLogger(GrpcSendTask.class.getName());

    private final WeakReference<Activity> activityReference;
    private ManagedChannel channel;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Context applicationContext;

    GrpcSendTask(Activity activity) {
        this.activityReference = new WeakReference<Activity>(activity);
        this.applicationContext = activity.getApplicationContext();
    }

    public void executeInBackground(String host, String port) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    inBackground(host, port);
                } catch (Exception e) {
                    // Handle any exceptions
                    logger.log(WARNING, "executeInBackground failed");
                }
            }
        });
    }

    void inBackground(String... params) throws Exception {
        String host = params[0];
        String portStr = params[1];
        int port = Integer.parseInt(portStr);
        try {
            channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
            FileServiceGrpc.FileServiceStub stub = FileServiceGrpc.newStub(channel);
            StreamObserver<FileUploadRequest> streamObserver =
                    stub.withDeadlineAfter(Constants.GRPC_SHORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .uploadFile(new FileUploadObserver());
            BundleTransmission bundleTransmission;
            bundleTransmission = new BundleTransmission(Paths.get(applicationContext.getApplicationInfo().dataDir));
            BundleDTO toSend = bundleTransmission.generateBundleForTransmission();
            logger.log(INFO, "[BDA] An outbound bundle generated with id: " + toSend.getBundleId());
            FileUploadRequest metadata = FileUploadRequest.newBuilder()
                    .setMetadata(MetaData.newBuilder().setName(toSend.getBundleId()).setType("bundle").build()).build();
            streamObserver.onNext(metadata);

//      upload file as chunk
            logger.log(INFO, "Started file transfer");
            FileInputStream inputStream;
            inputStream = new FileInputStream(toSend.getBundle().getSource());
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
            postExecute(applicationContext.getString(R.string.complete));
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            pw.flush();
            String s = sw.toString();
            String result = String.format(applicationContext.getString(R.string.failed_file_transfer), s);
            postExecute(result);
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
                resultText.append(String.format("Send finished: %s\n", result));
            }
        });
        //call UI thread
    }
}
