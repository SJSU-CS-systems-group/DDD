package net.discdd.server.commands.bundleuploader;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import net.discdd.bundletransport.service.BundleMetaData;
import net.discdd.bundletransport.service.BundleServiceGrpc;
import net.discdd.bundletransport.service.BundleUploadRequest;
import net.discdd.bundletransport.service.BundleUploadResponse;
import org.springframework.boot.CommandLineRunner;
import picocli.CommandLine;

import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

@CommandLine.Command(name = "upload-bundle", description = "Upload a bundle to the server")
public class BundleUploader implements CommandLineRunner, Callable<Integer> {
    private static final Logger logger = Logger.getLogger(BundleUploader.class.getName());
    @CommandLine.Parameters(description = "upload-bundle")
    String ignore;
    @CommandLine.Parameters(description = "Bundle file to upload", arity = "1")
    File bundle;
    @CommandLine.Parameters(description = "BundleServer host")
    String bundleServerHost;
    @CommandLine.Parameters(description = "BundleServer post")
    int bundleServerPort;

    public void run(String[] args) {
        String command = args.length > 0 ? args[0] : null;

        if (command == null) {
            return;
        }

        if (command.equals("upload-bundle")) {
            System.exit(new CommandLine(this).execute(args));
        }
    }

    @Override
    public Integer call() throws Exception {
        var channel = ManagedChannelBuilder.forAddress(bundleServerHost, bundleServerPort).usePlaintext().build();
        BundleServiceGrpc.BundleServiceStub stub = BundleServiceGrpc.newStub(channel);
        CountDownLatch latch = new CountDownLatch(1);
        StreamObserver<BundleUploadRequest> streamObserver =
                stub.uploadBundle(new StreamObserver<BundleUploadResponse>() {

                    @Override
                    public void onNext(BundleUploadResponse bundleUploadResponse) {
                        logger.log(INFO, bundleUploadResponse.toString());
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        logger.log(SEVERE, throwable.getMessage());
                        System.exit(3);
                    }

                    @Override
                    public void onCompleted() {
                        logger.log(INFO, "Complete");
                        latch.countDown();

                    }
                });
        BundleUploadRequest metadata = BundleUploadRequest.newBuilder()
                .setMetadata(BundleMetaData.newBuilder().setBid(bundle.getName()).setTransportId("CLI").build())
                .build();
        streamObserver.onNext(metadata);

        // upload file in chunks
        logger.log(INFO, "Started file transfer");
        FileInputStream inputStream = new FileInputStream(bundle.getAbsolutePath());
        int chunkSize = 1000 * 1000 * 4;
        byte[] bytes = new byte[chunkSize];
        int size = 0;
        while ((size = inputStream.read(bytes)) != -1) {
            logger.log(WARNING, "Sending chunk size: " + size);
            BundleUploadRequest uploadRequest = BundleUploadRequest.newBuilder().setFile(
                   net.discdd.bundletransport.service.File.newBuilder().setContent(ByteString.copyFrom(bytes, 0, size))
                            .build()).build();
            streamObserver.onNext(uploadRequest);
        }
        inputStream.close();
        logger.log(INFO, "Files are sent");
        streamObserver.onCompleted();
        latch.await();
        return null;
    }
}
