package net.discdd.bundletransport;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

import com.google.protobuf.ByteString;

import net.discdd.bundlerouting.service.BundleUploadResponseObserver;
import net.discdd.grpc.BundleChunk;
import net.discdd.grpc.BundleDownloadRequest;
import net.discdd.grpc.BundleDownloadResponse;
import net.discdd.grpc.BundleExchangeServiceGrpc;
import net.discdd.grpc.BundleInventoryRequest;
import net.discdd.grpc.BundleSender;
import net.discdd.grpc.BundleSenderType;
import net.discdd.grpc.BundleServerServiceGrpc;
import net.discdd.grpc.BundleUploadRequest;
import net.discdd.grpc.BundleUploadResponse;
import net.discdd.grpc.EncryptedBundleId;
import net.discdd.utils.Constants;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;

import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

public class TransportToBundleServerManager implements Runnable {

    private static final Logger logger = Logger.getLogger(TransportToBundleServerManager.class.getName());
    private final BundleSender transportSenderId;
    private final Path fromClientPath;
    private final Path fromServerPath;
    private final Function<Void, Void> connectComplete;
    private final String transportTarget;

    public TransportToBundleServerManager(Path filePath, String host, String port, String transportId,
                                          Function<Void, Void> connectComplete) {
        this.connectComplete = connectComplete;
        this.transportTarget = host + ":" + port;
        this.transportSenderId = BundleSender.newBuilder().setId(transportId).setType(
                BundleSenderType.TRANSPORT).build();
        this.fromClientPath = filePath.resolve("BundleTransmission/server");
        this.fromServerPath = filePath.resolve("BundleTransmission/client");
    }

    @Override
    public void run() {
        var channel = ManagedChannelBuilder.forTarget(transportTarget).usePlaintext().build();
        var bsStub = BundleServerServiceGrpc.newBlockingStub(channel);
        var exchangeStub = BundleExchangeServiceGrpc.newStub(channel);
        var bundlesFromClients = populateListFromPath(fromClientPath);
        var bundlesFromServer = populateListFromPath(fromServerPath);
        var inventoryResponse = bsStub.withDeadlineAfter(Constants.GRPC_SHORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .bundleInventory(BundleInventoryRequest.newBuilder().setSender(transportSenderId)
                                     .addAllBundlesFromClientsOnTransport(bundlesFromClients)
                                     .addAllBundlesFromServerOnTransport(bundlesFromServer)
                                     .build());
        for (var toDelete: inventoryResponse.getBundlesToDeleteList()) {
            var delPath = fromServerPath.resolve(toDelete.getEncryptedId());
            try {
                Files.delete(delPath);
            } catch (IOException e) {
                logger.log(SEVERE, "Failed to delete file: " + delPath, e);
            }
        }

        for (var toSend: inventoryResponse.getBundlesToUploadList()) {
            var path = fromClientPath.resolve(toSend.getEncryptedId());
            StreamObserver<BundleUploadResponse> responseObserver = null;
            try (var is = Files.newInputStream(path, StandardOpenOption.READ)){
                responseObserver = new BundleUploadResponseObserver();
                var uploadRequestStreamObserver =
                        exchangeStub.withDeadlineAfter(Constants.GRPC_LONG_TIMEOUT_MS,
                                                       TimeUnit.MILLISECONDS)
                                .uploadBundle(responseObserver);
                uploadRequestStreamObserver.onNext(BundleUploadRequest.newBuilder().setBundleId(toSend).build());
                byte[] data = new byte[1024*1024];
                int rc;
                while ((rc = is.read(data)) > 0) {
                    uploadRequestStreamObserver.onNext(BundleUploadRequest.newBuilder().setChunk(
                            BundleChunk.newBuilder().setChunk(ByteString.copyFrom(data, 0, rc))).build());
                }
            } catch (IOException e) {
                logger.log(SEVERE, "Failed to upload file: " + path, e);
                if (responseObserver != null) responseObserver.onError(e);
            }
            if (responseObserver != null) responseObserver.onCompleted();
        }

        for (var toReceive: inventoryResponse.getBundlesToDownloadList()) {
            var path = fromServerPath.resolve(toReceive.getEncryptedId());
            try (OutputStream os = Files.newOutputStream(path, StandardOpenOption.CREATE,
                                                         StandardOpenOption.TRUNCATE_EXISTING)) {
                exchangeStub.withDeadlineAfter(Constants.GRPC_LONG_TIMEOUT_MS,
                                               TimeUnit.MILLISECONDS).downloadBundle(
                        BundleDownloadRequest.newBuilder().setBundleId(toReceive).setSender(transportSenderId).build(),
                        new StreamObserver<>() {
                            @Override
                            public void onNext(BundleDownloadResponse value) {
                                try {
                                    os.write(value.getChunk().getChunk().toByteArray());
                                } catch (IOException e) {
                                    onError(e);
                                }
                            }

                            @Override
                            public void onError(Throwable t) {
                                logger.log(SEVERE, "Failed to download file: " + path, t);
                            }

                            @Override
                            public void onCompleted() {
                                logger.log(INFO, "Downloaded " + path);
                            }
                        });
            } catch (IOException e) {
                logger.log(SEVERE, "Failed to download file: " + path, e);
            }
        }
        logger.log(INFO, "Connect server completed");
        connectComplete.apply(null);
    }

    private List<EncryptedBundleId> populateListFromPath(Path path) {
        var listOfBundleIds = new ArrayList<EncryptedBundleId>();
        var bundles = path.toFile().listFiles();
        if (bundles != null) for (File bundle : bundles) {
            listOfBundleIds.add(EncryptedBundleId.newBuilder().setEncryptedId(bundle.getName()).build());
        }
        return listOfBundleIds;
    }
}
