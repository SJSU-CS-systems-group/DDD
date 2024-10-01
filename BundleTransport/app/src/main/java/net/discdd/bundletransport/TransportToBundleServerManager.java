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
import net.discdd.transport.TransportSecurity;
import net.discdd.utils.Constants;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.logging.Logger;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.TlsChannelCredentials;
import io.grpc.ManagedChannelBuilder;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.StreamObserver;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

public class TransportToBundleServerManager implements Runnable {

    private static final Logger logger = Logger.getLogger(TransportToBundleServerManager.class.getName());
    public static final String RECENCY_BLOB_BIN = "recencyBlob.bin";
    private final BundleSender transportSenderId;
    private final Path fromClientPath;
    private final Path fromServerPath;
    private final Function<Void, Void> connectComplete;
    private final Function<Exception, Void> connectError;
    private final String transportTarget;
    private final TransportSecurity transportSecurity;

    public TransportToBundleServerManager(Path filePath, String host, String port, TransportSecurity transportSecurity, Function<Void,
            Void> connectComplete, Function<Exception, Void> connectError) {
        this.connectComplete = connectComplete;
        this.transportSecurity = transportSecurity;
        this.connectError = connectError;
        this.transportTarget = host + ":" + port;
        this.transportSenderId =
                BundleSender.newBuilder().setId(transportSecurity.getTransportID()).setType(BundleSenderType.TRANSPORT).build();
        this.fromClientPath = filePath.resolve("BundleTransmission/server");
        this.fromServerPath = filePath.resolve("BundleTransmission/client");
    }

    @Override
    public void run() {
        logger.log(INFO, "Connect server started");

        try {
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            trustStore.setCertificateEntry("transportCert", transportSecurity.getCertificate());
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");

            sslContext.init(null, new TrustManager[] {
                    new X509TrustManager() {
                        private final X509TrustManager defaultTrustManager = (X509TrustManager) trustManagerFactory.getTrustManagers()[0];

                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                            // No client validation required
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                            // Validate the server's certificate
                            defaultTrustManager.checkServerTrusted(chain, authType);

                            // Extract and check the issuer of the server certificate
                            X509Certificate serverCert = chain[0]; // Get the server's certificate
                            String issuer = serverCert.getIssuerX500Principal().getName();
                            String expectedIssuer = "CN=ifX_H_iaDehu9IacFOUg-AyzYWo=";

                            if (!issuer.equals(expectedIssuer)) {
                                logger.log(SEVERE, "Server certificate issued by an untrusted issuer: " + issuer, new CertificateException());
                            }
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return defaultTrustManager.getAcceptedIssuers();
                        }
                    }
            }, null);

            var channel = OkHttpChannelBuilder.forTarget(transportTarget)
                    .sslSocketFactory(sslContext.getSocketFactory())
                    .useTransportSecurity()
                    .build();
            var bsStub = BundleServerServiceGrpc.newBlockingStub(channel);
            var exchangeStub = BundleExchangeServiceGrpc.newStub(channel);
            var blockingExchangeStub = BundleExchangeServiceGrpc.newBlockingStub(channel);
            var bundlesFromClients = populateListFromPath(fromClientPath);
            var bundlesFromServer = populateListFromPath(fromServerPath);

            try {
                var inventoryResponse = bsStub.withDeadlineAfter(Constants.GRPC_SHORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .bundleInventory(BundleInventoryRequest.newBuilder().setSender(transportSenderId)
                                                 .addAllBundlesFromClientsOnTransport(bundlesFromClients)
                                                 .addAllBundlesFromServerOnTransport(bundlesFromServer).build());

                try {
                    if (!Files.exists(fromServerPath) || !Files.isDirectory(fromClientPath)) {
                        Files.createDirectories(fromServerPath);
                        Files.createDirectories(fromClientPath);
                    }
                } catch (Exception e) {
                    logger.log(SEVERE, "Failed to get inventory", e);
                }

                for (var toDelete : inventoryResponse.getBundlesToDeleteList()) {
                    var delPath = fromServerPath.resolve(toDelete.getEncryptedId());
                    try {
                        Files.delete(delPath);
                    } catch (IOException e) {
                        logger.log(SEVERE, "Failed to delete file: " + delPath, e);
                    }
                }

                for (var toSend : inventoryResponse.getBundlesToUploadList()) {
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
                    } catch (IOException e) {
                        logger.log(SEVERE, "Failed to upload file: " + path, e);
                        if (responseObserver != null) responseObserver.onError(e);
                    }

                    if (responseObserver != null) {
                        responseObserver.onCompleted();
                        Files.delete(path);
                    }
                }

                for (var toReceive : inventoryResponse.getBundlesToDownloadList()) {
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
            } catch (Exception e) {
                logger.log(SEVERE, "error: " + e.getMessage());
                connectError.apply(e);
            }

            var recencyBlobReq = GetRecencyBlobRequest.newBuilder().setSender(transportSenderId).build();

            var recencyBlob = blockingExchangeStub.withDeadlineAfter(Constants.GRPC_SHORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .getRecencyBlob(recencyBlobReq);

            Path blobPath = fromServerPath.resolve(RECENCY_BLOB_BIN);
            try (var os = Files.newOutputStream(blobPath, StandardOpenOption.CREATE,
                                                StandardOpenOption.TRUNCATE_EXISTING)) {
                logger.log(INFO, "Writing blob to " + blobPath);
                recencyBlob.writeTo(os);
            } catch (IOException e) {
                logger.log(SEVERE, "Failed to write recency blob", e);
            }

            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                logger.log(SEVERE, "could not shutdown channel, error: " + e.getMessage() + ", cause: " + e.getCause());
            }
            logger.log(INFO, "Connect server completed");
            connectComplete.apply(null);
        } catch (KeyStoreException | CertificateException | NoSuchAlgorithmException | RuntimeException | KeyManagementException e) {
            logger.log(SEVERE, "Failed to load truststore", e);
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
