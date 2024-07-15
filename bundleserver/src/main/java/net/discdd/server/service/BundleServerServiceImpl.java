package net.discdd.server.service;

import com.google.protobuf.ByteString;
import edu.sjsu.ddd.bundleserver.service.BundleDownloadRequest;
import edu.sjsu.ddd.bundleserver.service.BundleDownloadResponse;
import edu.sjsu.ddd.bundleserver.service.BundleList;
import edu.sjsu.ddd.bundleserver.service.BundleMetaData;
import edu.sjsu.ddd.bundleserver.service.BundleServiceGrpc.BundleServiceImplBase;
import edu.sjsu.ddd.bundleserver.service.BundleUploadRequest;
import edu.sjsu.ddd.bundleserver.service.BundleUploadResponse;
import edu.sjsu.ddd.bundleserver.service.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

@GrpcService
public class BundleServerServiceImpl extends BundleServiceImplBase {
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
            String transportID;

            @Override
            public void onNext(BundleUploadRequest BundleUploadRequest) {
                try {
                    if (BundleUploadRequest.hasMetadata()) {
                        transportID = BundleUploadRequest.getMetadata().getTransportId();
                        writer = getFilePath(BundleUploadRequest);
                    } else {
                        writeFile(writer, BundleUploadRequest.getFile().getContent());
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
                bundleTransmission.processReceivedBundles(transportID);
                responseObserver.onCompleted();
            }
        };
    }

    private OutputStream getFilePath(BundleUploadRequest request) throws IOException {
        String fileName = request.getMetadata().getBid();
        File directoryReceive = Path.of(ReceiveDir, request.getMetadata().getTransportId()).toFile();
        if (!directoryReceive.exists()) {
            directoryReceive.mkdirs();
        }
        return Files.newOutputStream(
                Paths.get(ReceiveDir).resolve(request.getMetadata().getTransportId()).resolve(fileName),
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
        String transportFiles = request.getBundleList();
        logger.log(INFO, "[BundleServerService] bundles on transport" + transportFiles);
        if (null != request.getTransportId())
            logger.log(INFO, "[BundleServerService] Request from Transport id :" + request.getTransportId());
        else logger.log(INFO, "[BundleServerService] Request from Client id :" + request.getClientId());
        String[] filesOnTransport = null;
        Set<String> filesOnTransportSet = Collections.<String>emptySet();
        if (!transportFiles.isEmpty()) {
            filesOnTransport = transportFiles.split(",");
            List<String> filesOnTransportList = Arrays.asList(filesOnTransport);
            filesOnTransportSet = new HashSet<String>(filesOnTransportList);
        }

        List<File> bundlesList = bundleTransmission.getBundlesForTransmission(request.getTransportId());
        logger.log(FINE, "Downloaded " + bundleTransmission);
        if (bundlesList.isEmpty()) {
            BundleTransferDTO bundleTransferDTO = null;
            try {
                bundleTransferDTO = bundleTransmission.generateBundlesForTransmission(request.getTransportId(),
                                                                                      filesOnTransportSet);
            } catch (Exception e) {
                logger.log(WARNING, "[BundleServerService] Error generating bundles for transmission", e);
                responseObserver.onError(e);
                responseObserver.onCompleted();
                return;
            }
            if (bundleTransferDTO.getBundles().isEmpty()) {
                responseObserver.onNext(BundleDownloadResponse.newBuilder().setStatus(Status.SUCCESS).build());
            } else {
                BundleDownloadResponse response = BundleDownloadResponse.newBuilder().setBundleList(
                                BundleList.newBuilder().setBundleList(String.join(", ",
                                                                                  bundleTransferDTO.getDeletionSet())))
                        .build();
                logger.log(WARNING,
                           "[BundleServerService] Sending " + String.join(", ", bundleTransferDTO.getDeletionSet()) +
                                   " to delete on Transport id :" + request.getTransportId());
                responseObserver.onNext(response);
            }
            responseObserver.onCompleted();
        } else { // can be removed by removing transport directories

            for (File bundle : bundlesList) {
                if (!filesOnTransportSet.contains(bundle.getName())) {
                    logger.log(WARNING, "[BundleServerService]Downloading " + bundle.getName() + " to Transport id :" +
                            request.getTransportId());
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
                                edu.sjsu.ddd.bundleserver.service.File.newBuilder().setContent(bytes)).build());
                    });
                    if (ex != null) ex.printStackTrace();
                    responseObserver.onCompleted();
                }
            }
            logger.log(INFO, "[BundleServerService] All bundles were transferred completing status success");
            responseObserver.onNext(BundleDownloadResponse.newBuilder().setStatus(Status.SUCCESS).build());
            responseObserver.onCompleted();
        }
    }
}