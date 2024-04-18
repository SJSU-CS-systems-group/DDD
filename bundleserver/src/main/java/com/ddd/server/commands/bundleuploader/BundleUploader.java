package com.ddd.server.commands.bundleuploader;

import com.google.protobuf.ByteString;
import edu.sjsu.ddd.bundleserver.service.BundleMetaData;
import edu.sjsu.ddd.bundleserver.service.BundleServiceGrpc;
import edu.sjsu.ddd.bundleserver.service.BundleUploadRequest;
import edu.sjsu.ddd.bundleserver.service.BundleUploadResponse;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

@CommandLine.Command(name = "upload-bundle", description = "Upload a bundle to the server")
public class BundleUploader implements CommandLineRunner, Callable<Integer> {
    @CommandLine.Parameters (description = "upload-bundle")
    String ignore;
    @CommandLine.Parameters (description = "Bundle file to upload", arity = "1")
    File bundle;
    @CommandLine.Parameters (description = "BundleServer host")
    String bundleServerHost;
    @CommandLine.Parameters (description = "BundleServer post")
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
        StreamObserver<BundleUploadRequest> streamObserver = stub.uploadBundle(new StreamObserver<BundleUploadResponse>() {

            @Override
            public void onNext(BundleUploadResponse bundleUploadResponse) {
                System.out.println(bundleUploadResponse);
            }

            @Override
            public void onError(Throwable throwable) {
                System.out.println(throwable);
                System.exit(3);
            }

            @Override
            public void onCompleted() {
                System.out.println("Complete");
                latch.countDown();
            }
        });
        BundleUploadRequest metadata = BundleUploadRequest
                .newBuilder()
                .setMetadata(BundleMetaData
                        .newBuilder()
                        .setBid(bundle.getName())
                        .setTransportId("CLI")
                        .build())
                .build();
        streamObserver.onNext(metadata);

        // upload file in chunks
        System.out.println("Started file transfer");
        FileInputStream inputStream = new FileInputStream(bundle.getAbsolutePath());
        int chunkSize = 1000 * 1000 * 4;
        byte[] bytes = new byte[chunkSize];
        int size = 0;
        while ((size = inputStream.read(bytes)) != -1) {
            System.out.println("Sending chunk size: " + size);
            BundleUploadRequest uploadRequest = BundleUploadRequest
                    .newBuilder()
                    .setFile(edu.sjsu.ddd.bundleserver.service.File
                    .newBuilder()
                    .setContent(ByteString
                    .copyFrom(bytes, 0, size))
                    .build())
                    .build();
            streamObserver.onNext(uploadRequest);
        }
        inputStream.close();
        System.out.println("Files are sent");
        streamObserver.onCompleted();
        latch.await();
        return null;
    }
}
