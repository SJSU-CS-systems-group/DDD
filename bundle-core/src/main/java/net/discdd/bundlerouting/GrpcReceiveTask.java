package net.discdd.bundlerouting;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import net.discdd.bundletransport.service.BundleDownloadRequest;
import net.discdd.bundletransport.service.BundleDownloadResponse;
import net.discdd.bundletransport.service.BundleServiceGrpc;
import net.discdd.utils.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.WARNING;

public class GrpcReceiveTask {
    private static final Logger logger = Logger.getLogger(GrpcReceiveTask.class.getName());

    private String host, receiveDir, transportId;
    private int port;
    private boolean receiveBundles, statusComplete;
    private ManagedChannel channel;

    public GrpcReceiveTask(String host, int port, String transportId, String receiveDir) {
        logger.log(INFO, "Initializing GrpcReceiveTask...");
        this.host = host;
        this.port = port;
        this.transportId = transportId;
        this.receiveDir = receiveDir;
    }

    public Exception run() {
        Exception thrown = null;
        try {
            executeTask();
            logger.log(FINE, "Executed receive task");
        } catch (Exception e) {
            thrown = e;
        }

        try {
            postExecuteTask();
        } catch (InterruptedException e) {
            logger.log(WARNING, "Failed to shutdown GrpcReceiveTask channel: " + e.getMessage());
        }

        return thrown;
    }

    private void executeTask() throws Exception {
        logger.log(INFO, "Executing GrpcReceiveTask...");

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
                logger.log(FINE, "onNext: called with " + response.toString());
                if (response.hasBundleList()) {
                    logger.log(FINE, "Got list for deletion");
                    List<String> toDelete = Arrays.asList(response.getBundleList().getBundleListList().toArray(new String[0]));
                    if (!toDelete.isEmpty()) {
                        File clientDir = new File(receiveDir);
                        for (File bundle : clientDir.listFiles()) {
                            if (toDelete.contains(bundle.getName())) {
                                logger.log(INFO, "Deleting file: " + bundle.getName());
                                bundle.delete();
                            }
                        }
                    } else {
                        logger.log(INFO, "No bundles to delete");
                    }
                } else if (response.hasStatus()) {
                    logger.log(INFO, "Status found: terminating loop");
                    receiveBundles = false;
                } else if (response.hasMetadata()) {
                    try {
                        logger.log(INFO, "Downloading chunk of: " + response.getMetadata().getBid());
                        writer = FileUtils.getFilePath(response, receiveDir);
                    } catch (IOException e) {
                        logger.log(WARNING,
                                "/GrpcReceiveTask.java -> executeTask() -> onNext() IOException: " + e.getMessage());
                    }
                } else {
                    try {
                        FileUtils.writeFile(writer, response.getFile().getContent());
                    } catch (IOException e) {
                        logger.log(WARNING,
                                "/GrpcReceiveTask.java -> executeTask() -> onNext() IOException: " + e.getMessage());
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                logger.log(SEVERE, "Error downloading file: " + t.getMessage(), t);
                receiveBundles = false;
                thrown[0] = t;
                if (fileOutputStream != null) {
                    try {
                        fileOutputStream.close();
                    } catch (IOException e) {
                        logger.log(WARNING, "/GrpcReceiveTask.java -> executeTask() -> onError() IOException: " +
                                e.getMessage());

                    }
                }
            }

            @Override
            public void onCompleted() {
                if (writer != null) {
                    FileUtils.closeFile(writer);
                }

                logger.log(INFO, "File download complete");
                statusComplete = true;
            }
        };

        while (receiveBundles) {
            if (statusComplete) {
                logger.log(INFO, "/GrpcReceiveTask.java -> executeTask() receiveBundles = " + receiveBundles);

                File dir = new File(receiveDir);
                List<String> files = Arrays.stream(dir.listFiles(f -> f.length() > 0)).map(File::getName).collect(
                        Collectors.toList());
                BundleDownloadRequest request =
                        BundleDownloadRequest.newBuilder().setTransportId(transportId).addAllBundleList(files)
                                .build();

                stub.downloadBundle(request, downloadObserver);

                logger.log(FINE, "Receive task complete");
                statusComplete = false;
            }
        }
        logger.log(SEVERE, "Error thrown: ", thrown[0]);
        if (thrown[0] != null) {
            throw new Exception(thrown[0].getMessage());
        }
    }

    private void postExecuteTask() throws InterruptedException {
        channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
    }
}
