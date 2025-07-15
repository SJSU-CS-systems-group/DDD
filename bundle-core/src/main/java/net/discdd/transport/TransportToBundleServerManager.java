package net.discdd.transport;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.StreamObserver;
import net.discdd.bundlerouting.service.BundleUploadResponseObserver;
import net.discdd.grpc.BundleChunk;
import net.discdd.grpc.BundleDownloadRequest;
import net.discdd.grpc.BundleDownloadResponse;
import net.discdd.grpc.BundleExchangeServiceGrpc;
import net.discdd.grpc.BundleInventoryRequest;
import net.discdd.grpc.BundleSenderType;
import net.discdd.grpc.BundleServerServiceGrpc;
import net.discdd.grpc.BundleUploadRequest;
import net.discdd.grpc.BundleUploadResponse;
import net.discdd.grpc.CrashReportRequest;
import net.discdd.grpc.EncryptedBundleId;
import net.discdd.grpc.GetRecencyBlobRequest;
import net.discdd.pathutils.TransportPaths;
import net.discdd.tls.DDDTLSUtil;
import net.discdd.tls.DDDX509ExtendedTrustManager;
import net.discdd.tls.GrpcSecurityKey;
import net.discdd.utils.Constants;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

public class TransportToBundleServerManager {

    private static final Logger logger = Logger.getLogger(TransportToBundleServerManager.class.getName());
    public static final String RECENCY_BLOB_BIN = "recencyBlob.bin";
    private final Path fromClientPath;
    private final Path fromServerPath;
    private final Path crashReportsPath;
    private final String serverHost;
    private final int serverPort;
    private final GrpcSecurityKey grpcSecurityKey;

    public TransportToBundleServerManager(GrpcSecurityKey grpcSecurityKey,
                                          TransportPaths transportPaths,
                                          String host,
                                          String port) {
        this.grpcSecurityKey = grpcSecurityKey;
        this.serverHost = host;
        this.serverPort = Integer.parseInt(port);
        this.fromClientPath = transportPaths.toServerPath;
        this.fromServerPath = transportPaths.toClientPath;
        this.crashReportsPath = transportPaths.crashReportPath;
    }

    public static class ExchangeResult {
        public int uploadCount = 0; // the count actually uploaded
        public int toUploadCount = 0; // the count we were supposed to upload
        public int downloadCount = 0;
        public int toDownloadCount = 0;
        public int deleteCount = 0;
    }

    public ExchangeResult doExchange() throws Exception {
        ManagedChannel channel = null;
        ExchangeResult exchangeResult = new ExchangeResult();
        try {
            var sslClientContext = SSLContext.getInstance("TLS");
            sslClientContext.init(DDDTLSUtil.getKeyManagerFactory(grpcSecurityKey.grpcKeyPair, grpcSecurityKey.grpcCert)
                                          .getKeyManagers(),
                                  new TrustManager[] { new DDDX509ExtendedTrustManager(true) },
                                  new SecureRandom());

            channel = OkHttpChannelBuilder.forAddress(serverHost, serverPort)
                    .hostnameVerifier((host, session) -> true)
                    .useTransportSecurity()
                    .sslSocketFactory(sslClientContext.getSocketFactory())
                    .build();

            var bsStub = BundleServerServiceGrpc.newBlockingStub(channel);

            var exchangeStub = BundleExchangeServiceGrpc.newStub(channel);
            var blockingExchangeStub = BundleExchangeServiceGrpc.newBlockingStub(channel);
            var bundlesFromClients = populateListFromPath(fromClientPath);
            var bundlesFromServer = populateListFromPath(fromServerPath);

            if (crashReportsPath.toFile().exists()) {
                var collectedCrashes = bsStub.withDeadlineAfter(Constants.GRPC_LONG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .crashReports(CrashReportRequest.newBuilder()
                                              .setCrashReportData(ByteString.copyFrom(Files.readAllBytes(
                                                      crashReportsPath)))
                                              .build());
            }
            var inventoryResponse = bsStub.withDeadlineAfter(Constants.GRPC_LONG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .bundleInventory(BundleInventoryRequest.newBuilder()
                                             .addAllBundlesFromClientsOnTransport(bundlesFromClients)
                                             .addAllBundlesFromServerOnTransport(bundlesFromServer)
                                             .build());

            exchangeResult.downloadCount = inventoryResponse.getBundlesToDeleteCount();
            processDeleteBundles(inventoryResponse.getBundlesToDeleteList());
            exchangeResult.toUploadCount = inventoryResponse.getBundlesToUploadCount();
            exchangeResult.uploadCount = processUploadBundles(inventoryResponse.getBundlesToUploadList(), exchangeStub);
            exchangeResult.toDownloadCount = inventoryResponse.getBundlesToDownloadCount();
            exchangeResult.downloadCount =
                    processDownloadBundles(inventoryResponse.getBundlesToDownloadList(), exchangeStub);
            processRecencyBlob(blockingExchangeStub);

            logger.log(INFO, "Connect server completed");
        } finally {
            try {
                if (channel != null) {
                    channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                logger.log(SEVERE, "could not shutdown channel, error: " + e.getMessage() + ", cause: " + e.getCause());
            }
        }
        return exchangeResult;
    }

    private void processRecencyBlob(BundleExchangeServiceGrpc.BundleExchangeServiceBlockingStub blockingExchangeStub) {
        var recencyBlobReq = GetRecencyBlobRequest.newBuilder().build();
        var recencyBlob = blockingExchangeStub.withDeadlineAfter(Constants.GRPC_SHORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .getRecencyBlob(recencyBlobReq);
        Path blobPath = fromServerPath.resolve(RECENCY_BLOB_BIN);
        try (var os = Files.newOutputStream(blobPath,
                                            StandardOpenOption.CREATE,
                                            StandardOpenOption.TRUNCATE_EXISTING)) {
            logger.log(INFO, "Writing blob to " + blobPath);
            recencyBlob.writeTo(os);
        } catch (IOException e) {
            logger.log(SEVERE, "Failed to write recency blob", e);
        }
    }

    private int processDownloadBundles(List<EncryptedBundleId> bundlesToDownloadList,
                                       BundleExchangeServiceGrpc.BundleExchangeServiceStub exchangeStub) {
        int downloadCount = 0;
        for (var toReceive : bundlesToDownloadList) {
            var path = fromServerPath.resolve(toReceive.getEncryptedId());
            try (OutputStream os = Files.newOutputStream(path,
                                                         StandardOpenOption.CREATE,
                                                         StandardOpenOption.TRUNCATE_EXISTING)) {
                var completion = new CompletableFuture<Boolean>();
                exchangeStub.withDeadlineAfter(Constants.GRPC_LONG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .downloadBundle(BundleDownloadRequest.newBuilder()
                                                .setBundleId(toReceive)
                                                .setSenderType(BundleSenderType.TRANSPORT)
                                                .build(), new StreamObserver<>() {
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
                                var level = SEVERE;
                                if (t instanceof StatusRuntimeException sre) {
                                    if (sre.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                                        // this is not an unexpected error, since we probe for bundles that we
                                        // hope are there
                                        level = Level.FINE;
                                    }
                                }
                                logger.log(level, "Failed to download file: " + path, t);
                                completion.completeExceptionally(t);
                            }

                            @Override
                            public void onCompleted() {
                                logger.log(INFO, "Downloaded " + path);
                                completion.complete(true);
                            }
                        });

                completion.get(Constants.GRPC_LONG_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                downloadCount++;
            } catch (IOException | ExecutionException | InterruptedException | TimeoutException e) {
                logger.log(SEVERE, "Failed to download file: " + path, e);
            }
        }
        return downloadCount;
    }

    private int processUploadBundles(List<EncryptedBundleId> bundlesToUploadList,
                                     BundleExchangeServiceGrpc.BundleExchangeServiceStub exchangeStub) {
        int uploadCount = 0;
        for (var toSend : bundlesToUploadList) {
            var path = fromClientPath.resolve(toSend.getEncryptedId());
            StreamObserver<BundleUploadResponse> responseObserver = null;
            try (var is = Files.newInputStream(path, StandardOpenOption.READ)) {
                responseObserver = new BundleUploadResponseObserver();
                var uploadRequestStreamObserver =
                        exchangeStub.withDeadlineAfter(Constants.GRPC_LONG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                                .uploadBundle(responseObserver);
                uploadRequestStreamObserver.onNext(BundleUploadRequest.newBuilder()
                                                           .setSenderType(BundleSenderType.TRANSPORT)
                                                           .build());
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
                logger.log(INFO, "Completed upload for bundle: " + toSend.getEncryptedId());
                responseObserver.onCompleted();
                logger.log(INFO, "Deleting bundle file: " + path);
                Files.delete(path);
                uploadCount++;
            } catch (IOException e) {
                logger.log(SEVERE, "Failed to upload file: " + path, e);
                if (responseObserver != null) responseObserver.onError(e);
            }
        }
        return uploadCount;
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
        if (bundles != null) {
            for (File bundle : bundles) {
                listOfBundleIds.add(EncryptedBundleId.newBuilder().setEncryptedId(bundle.getName()).build());
            }
        }
        return listOfBundleIds;
    }
}
