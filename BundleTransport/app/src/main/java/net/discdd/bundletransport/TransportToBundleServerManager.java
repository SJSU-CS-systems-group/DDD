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
import net.discdd.grpc.GetRecencyBlobRequest;
import net.discdd.pathutils.TransportPaths;
import net.discdd.utils.Constants;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.logging.Logger;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

public class TransportToBundleServerManager implements Runnable {

    private static final Logger logger = Logger.getLogger(TransportToBundleServerManager.class.getName());
    public static final String RECENCY_BLOB_BIN = "recencyBlob.bin";
    private final BundleSender transportSenderId;
    private final Path fromClientPath;
    private final Path fromServerPath;
    private final Function<Void, Void> connectComplete;
    private final Function<Exception, Void> connectError;
    private final String transportTarget;

    public TransportToBundleServerManager(TransportPaths transportPaths, String host, String port,
                                          Function<Void, Void> connectComplete,
                                          Function<Exception, Void> connectError) {
        this.connectComplete = connectComplete;
        this.connectError = connectError;
        this.transportTarget = host + ":" + port;
        this.transportSenderId =
                BundleSender.newBuilder().setId("bundle_transport").setType(BundleSenderType.TRANSPORT).build();
        this.fromClientPath = transportPaths.toServerPath;
        this.fromServerPath = transportPaths.toClientPath;
    }

    @Override
    public void run() {
        ManagedChannel channel = null;
        try {
            channel = Grpc.newChannelBuilder(transportTarget, InsecureChannelCredentials.create()).build();
            var bsStub = BundleServerServiceGrpc.newBlockingStub(channel);
            var exchangeStub = BundleExchangeServiceGrpc.newStub(channel);
            var blockingExchangeStub = BundleExchangeServiceGrpc.newBlockingStub(channel);
            var bundlesFromClients = populateListFromPath(fromClientPath);
            var bundlesFromServer = populateListFromPath(fromServerPath);

            var inventoryResponse = bsStub.withDeadlineAfter(Constants.GRPC_LONG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .bundleInventory(BundleInventoryRequest.newBuilder().setSender(transportSenderId)
                                             .addAllBundlesFromClientsOnTransport(bundlesFromClients)
                                             .addAllBundlesFromServerOnTransport(bundlesFromServer).build());

            processDeleteBundles(inventoryResponse.getBundlesToDeleteList());
            processUploadBundles(inventoryResponse.getBundlesToUploadList(), exchangeStub);
            processDownloadBundles(inventoryResponse.getBundlesToDownloadList(), exchangeStub);
            processRecencyBlob(blockingExchangeStub);

            logger.log(INFO, "Connect server completed");
            connectComplete.apply(null);
        } catch (IllegalArgumentException | StatusRuntimeException e) {
            logger.log(SEVERE, "Failed to connect to server", e);
            connectError.apply(e);
        } finally {
            try {
                if (channel != null) {
                    channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                logger.log(SEVERE, "could not shutdown channel, error: " + e.getMessage() + ", cause: " + e.getCause());
            }
        }
    }

    private void processRecencyBlob(BundleExchangeServiceGrpc.BundleExchangeServiceBlockingStub blockingExchangeStub) {
        var recencyBlobReq = GetRecencyBlobRequest.newBuilder().setSender(transportSenderId).build();
        var recencyBlob =
                blockingExchangeStub.withDeadlineAfter(Constants.GRPC_SHORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .getRecencyBlob(recencyBlobReq);

        Path blobPath = fromServerPath.resolve(RECENCY_BLOB_BIN);
        try (var os = Files.newOutputStream(blobPath, StandardOpenOption.CREATE,
                                            StandardOpenOption.TRUNCATE_EXISTING)) {
            logger.log(INFO, "Writing blob to " + blobPath);
            recencyBlob.writeTo(os);
        } catch (IOException e) {
            logger.log(SEVERE, "Failed to write recency blob", e);
        }
    }

    private void processDownloadBundles(List<EncryptedBundleId> bundlesToDownloadList, BundleExchangeServiceGrpc.BundleExchangeServiceStub exchangeStub) {
        for (var toReceive : bundlesToDownloadList) {
            var path = fromServerPath.resolve(toReceive.getEncryptedId());
            try (OutputStream os = Files.newOutputStream(path, StandardOpenOption.CREATE,
                                                         StandardOpenOption.TRUNCATE_EXISTING)) {
                var completion = new CompletableFuture<Boolean>();
                exchangeStub.withDeadlineAfter(Constants.GRPC_LONG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .downloadBundle(BundleDownloadRequest.newBuilder().setBundleId(toReceive)
                                                .setSender(transportSenderId).build(), new StreamObserver<>() {
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
                                completion.completeExceptionally(t);
                            }

                            @Override
                            public void onCompleted() {
                                logger.log(INFO, "Downloaded " + path);
                                completion.complete(true);
                            }
                        });

                completion.get(Constants.GRPC_LONG_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (IOException | ExecutionException | InterruptedException | TimeoutException e) {
                logger.log(SEVERE, "Failed to download file: " + path, e);

            }
        }
    }

    private void processUploadBundles(List<EncryptedBundleId> bundlesToUploadList, BundleExchangeServiceGrpc.BundleExchangeServiceStub exchangeStub) {
        for (var toSend : bundlesToUploadList) {
            var path = fromClientPath.resolve(toSend.getEncryptedId());
            StreamObserver<BundleUploadResponse> responseObserver = null;
            try (var is = Files.newInputStream(path, StandardOpenOption.READ)) {
                responseObserver = new BundleUploadResponseObserver();
                var uploadRequestStreamObserver =
                        exchangeStub.withDeadlineAfter(Constants.GRPC_LONG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                                .uploadBundle(responseObserver);
                uploadRequestStreamObserver.onNext(
                        BundleUploadRequest.newBuilder().setSender(transportSenderId).build());
                uploadRequestStreamObserver.onNext(BundleUploadRequest.newBuilder().setBundleId(toSend).build());
                byte[] data = new byte[1024 * 1024];
                int rc;
                while ((rc = is.read(data)) > 0) {
                    var uploadRequest = BundleUploadRequest.newBuilder()
                            .setChunk(BundleChunk.newBuilder().setChunk(ByteString.copyFrom(data, 0, rc)).build())
                            .build();
                    uploadRequestStreamObserver.onNext(uploadRequest);
                }
                uploadRequestStreamObserver.onCompleted();
                if (responseObserver != null) {
                    responseObserver.onCompleted();
                    Files.delete(path);
                }
            } catch (IOException e) {
                logger.log(SEVERE, "Failed to upload file: " + path, e);
                if (responseObserver != null) responseObserver.onError(e);
            }
        }
    }

    private void processDeleteBundles(List<EncryptedBundleId> bundlesToDeleteList) {
        for (var toDelete : bundlesToDeleteList) {
            var delPath = fromServerPath.resolve(toDelete.getEncryptedId());
            try {
                Files.delete(delPath);
            } catch (IOException e) {
                logger.log(SEVERE, "Failed to delete file: " + delPath, e);
            }
        }
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
