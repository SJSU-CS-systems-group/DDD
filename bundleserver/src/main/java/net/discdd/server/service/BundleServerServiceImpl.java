package net.discdd.server.service;

import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import net.discdd.bundlerouting.service.FileServiceImpl;
import net.discdd.bundletransport.service.BundleDownloadRequest;
import net.discdd.bundletransport.service.BundleDownloadResponse;
import net.discdd.bundletransport.service.BundleList;
import net.discdd.bundletransport.service.BundleMetaData;
import net.discdd.bundletransport.service.BundleSender;
import net.discdd.bundletransport.service.BundleServiceGrpc;
import net.discdd.bundletransport.service.BundleUploadRequest;
import net.discdd.bundletransport.service.BundleUploadResponse;
import net.discdd.bundletransport.service.Status;
import net.discdd.model.BundleTransferDTO;
import net.discdd.server.bundletransmission.BundleTransmission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

@GrpcService
public class BundleServerServiceImpl extends BundleServiceGrpc.BundleServiceImplBase {
    private String ReceiveDir;
    private String SendDir;
    private static final Logger logger = Logger.getLogger(BundleServerServiceImpl.class.getName());

    @Autowired
    private BundleTransmission bundleTransmission;

    @PostConstruct
    private void init() {
        java.io.File directoryReceive = new java.io.File(ReceiveDir);
        if (!directoryReceive.exists()) {
            directoryReceive.mkdirs();
        }

        java.io.File directorySend = new java.io.File(SendDir);
        if (!directorySend.exists()) {
            directorySend.mkdirs();
        }
    }

    @Value("${bundle-server.bundle-store-shared}")
    public void setDir(String bundleDir) {
        ReceiveDir = bundleDir + java.io.File.separator + "receive";
        SendDir = bundleDir + java.io.File.separator + "send";
    }

    @Override
    public StreamObserver<BundleUploadRequest> uploadBundle(StreamObserver<BundleUploadResponse> responseObserver) {
        return new StreamObserver<BundleUploadRequest>() {
            // upload context variables
            OutputStream writer;
            Status status = Status.IN_PROGRESS;
            BundleSender sender;

            @Override
            public void onNext(BundleUploadRequest bundleUploadRequest) {
                try {
                    if (bundleUploadRequest.hasMetadata()) {
                        sender = bundleUploadRequest.getMetadata().getSender();
                        writer = getFilePath(bundleUploadRequest);
                    } else {
                        writeFile(writer, bundleUploadRequest.getFile().getContent());
                    }
                } catch (IOException e) {
                    this.onError(e);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                logger.log(WARNING, "Error" + throwable.toString());
                status = Status.FAILED;
                this.onCompleted();
            }

            @Override
            public void onCompleted() {
                logger.log(INFO, "Complete");
                try {
                    closeFile(writer);
                } catch (IOException e) {
                    logger.log(SEVERE, "BundleServerServiceImpl.uploadBundle error: " + e.getMessage());
                }
                status = Status.IN_PROGRESS.equals(status) ? Status.SUCCESS : status;
                BundleUploadResponse response = BundleUploadResponse.newBuilder().setStatus(status).build();
                responseObserver.onNext(response);
                bundleTransmission.processReceivedBundles(sender);
                responseObserver.onCompleted();
            }
        };
    }

    private OutputStream getFilePath(BundleUploadRequest request) throws IOException {
        String fileName = request.getMetadata().getBid();
        File directoryReceive = Path.of(ReceiveDir, request.getMetadata().getSender().getId()).toFile();
        if (!directoryReceive.exists()) {
            directoryReceive.mkdirs();
        }
        return Files.newOutputStream(
                Paths.get(ReceiveDir).resolve(request.getMetadata().getSender().getId()).resolve(fileName),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void writeFile(OutputStream writer, ByteString content) throws IOException {
        writer.write(content.toByteArray());
        writer.flush();
    }

    private void closeFile(OutputStream writer) throws IOException {
        if (writer != null) {
            writer.close();
        }
    }

    @Override
    public void downloadBundle(BundleDownloadRequest request, StreamObserver<BundleDownloadResponse> responseObserver) {
        logger.log(INFO, "[BundleServerService] bundles on sender" + request.getBundleListList());
        logger.log(INFO,
                   "[BundleServerService] Request from " + FileServiceImpl.bundleSenderToString(request.getSender()));

        transmitBundles(request, responseObserver);
        logger.log(FINE, "Downloaded " + bundleTransmission);

        transmitDeletionBundleList(request, responseObserver);
    }

    private void transmitBundles(BundleDownloadRequest request,
                                 StreamObserver<BundleDownloadResponse> responseObserver) {
        Set<String> filesOnTransportSet = new HashSet<>(request.getBundleListList());
        List<File> bundlesToTransmitList = bundleTransmission.getBundlesForTransmission(request.getSender());
        for (File bundle : bundlesToTransmitList) {
            if (!filesOnTransportSet.contains(bundle.getName())) {
                logger.log(WARNING, "[BundleServerService]Downloading " + bundle.getName() + " to " +
                        FileServiceImpl.bundleSenderToString(request.getSender()));
                BundleMetaData bundleMetaData = BundleMetaData.newBuilder().setBid(bundle.getName()).build();
                responseObserver.onNext(BundleDownloadResponse.newBuilder().setMetadata(bundleMetaData).build());
                InputStream in;
                try {
                    in = new FileInputStream(bundle);
                } catch (Exception ex) {
                    responseObserver.onError(ex);
                    return;
                }
                StreamHandler handler = new StreamHandler(in);
                Exception ex = handler.handle(bytes -> {
                    responseObserver.onNext(BundleDownloadResponse.newBuilder().setFile(
                            net.discdd.bundletransport.service.File.newBuilder().setContent(bytes)).build());
                });
                if (ex != null) logger.log(SEVERE, "[BundleServerService] Error downloading bundle", ex);
                responseObserver.onCompleted();
            }
        }
        logger.log(INFO, "[BundleServerService] All bundles were transferred completing status success");
        responseObserver.onNext(BundleDownloadResponse.newBuilder().setStatus(Status.SUCCESS).build());
        responseObserver.onCompleted();
    }

    private void transmitDeletionBundleList(BundleDownloadRequest request,
                                            StreamObserver<BundleDownloadResponse> responseObserver) {
        Set<String> filesOnTransportSet = new HashSet<>(request.getBundleListList());
        BundleTransferDTO bundleToDelete = null;
        try {
            bundleToDelete =
                    bundleTransmission.generateBundlesForTransmission(request.getSender(), filesOnTransportSet);
        } catch (Exception e) {
            logger.log(WARNING, "[BundleServerService] Error generating bundles for transmission", e);
            responseObserver.onError(e);
            responseObserver.onCompleted();
            return;
        }
        if (bundleToDelete.getBundles().isEmpty()) {
            responseObserver.onNext(BundleDownloadResponse.newBuilder().setStatus(Status.SUCCESS).build());
        } else {
            BundleDownloadResponse response = BundleDownloadResponse.newBuilder()
                    .setBundleList(BundleList.newBuilder().addAllBundleList(bundleToDelete.getDeletionSet())).build();
            logger.log(WARNING, "[BundleServerService] Sending " + String.join(", ", bundleToDelete.getDeletionSet()) +
                    " to delete on " + FileServiceImpl.bundleSenderToString(request.getSender()));
            responseObserver.onNext(response);
        }
        responseObserver.onCompleted();
    }
}