package net.discdd.bundletransport;

import net.discdd.bundletransport.service.BundleUploadObserver;

import net.discdd.bundletransport.service.BundleMetaData;
import net.discdd.bundletransport.service.BundleServiceGrpc;
import net.discdd.bundletransport.service.BundleUploadRequest;
import com.google.protobuf.ByteString;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

public class GrpcSendTask {

    private static final Logger logger = Logger.getLogger(GrpcSendTask.class.getName());

    private String host, serverDir, transportId;
    private int port;
    private ManagedChannel channel;

    public GrpcSendTask(String host, int port, String transportId, String serverDir) {
        logger.log(INFO, "initializing grpcsendtask...");
        this.host = host;
        this.port = port;
        this.transportId = transportId;
        this.serverDir = serverDir;
    }

    public Exception run() {
        Exception thrown = null;
        try {
            executeTask();
        } catch (Exception e) {
            thrown = e;
        }

        try {
            postExecuteTask();
        } catch (InterruptedException e) {
            logger.log(WARNING, "Failed to shutdown GrpcSendTask channel: " + e.getMessage());
        }

        return thrown;
    }

    private void executeTask() throws IOException {
        logger.log(INFO, "executing grpcsendtask...");

        channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        BundleServiceGrpc.BundleServiceStub stub = BundleServiceGrpc.newStub(channel);
        StreamObserver<BundleUploadRequest> streamObserver = stub.uploadBundle(new BundleUploadObserver());
        File sendDir = new File(serverDir);
        logger.log(FINE, "received the stream observer: " + streamObserver);
        //get transport ID
        if (sendDir.exists()) {
            File[] bundles = sendDir.listFiles();
            if (bundles != null) {
                for (File bundle : bundles) {
                    BundleUploadRequest metadata = BundleUploadRequest.newBuilder().setMetadata(
                                    BundleMetaData.newBuilder().setBid(bundle.getName()).setTransportId(transportId).build())
                            .build();
                    streamObserver.onNext(metadata);

                    // upload file as chunk
                    logger.log(FINE, "Started file transfer");
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
                        logger.log(FINE, "Sending chunk size: " + size);
                        BundleUploadRequest uploadRequest = BundleUploadRequest.newBuilder().setFile(
                                net.discdd.bundletransport.service.File.newBuilder()
                                        .setContent(ByteString.copyFrom(bytes, 0, size)).build()).build();

                        streamObserver.onNext(uploadRequest);
                    }
                    inputStream.close();
                }
            }
        }

        logger.log(INFO, "Files are sent");
        streamObserver.onCompleted();
    }

    private void postExecuteTask() throws InterruptedException {
        channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
    }
}