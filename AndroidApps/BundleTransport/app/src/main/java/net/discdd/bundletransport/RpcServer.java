package net.discdd.bundletransport;

import com.google.protobuf.ByteString;
import io.grpc.Server;
import io.grpc.TlsServerCredentials;
import io.grpc.okhttp.OkHttpServerBuilder;
import io.grpc.stub.StreamObserver;
import net.discdd.bundlerouting.service.BundleExchangeServiceImpl;
import net.discdd.bundlesecurity.BundleOwnershipPSI;
import net.discdd.grpc.BundleChunk;
import net.discdd.grpc.BundleDownloadResponse;
import net.discdd.grpc.BundleSenderType;
import net.discdd.grpc.GetRecencyBlobRequest;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.PSIDownloadRequest;
import net.discdd.grpc.PSIElement;
import net.discdd.grpc.PSIRequest;
import net.discdd.grpc.PSIResponse;
import net.discdd.grpc.PublicKeyMap;
import net.discdd.pathutils.TransportPaths;
import net.discdd.tls.DDDTLSUtil;
import net.discdd.transport.TransportToBundleServerManager;

import javax.net.ssl.KeyManager;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

public class RpcServer {
    private static final Logger logger = Logger.getLogger(RpcServer.class.getName());

    private static final int port = 7777;
    //The number 40 was chosen because a SessionRecord only keeps track of the Private Keys of the
    // last 40 bundles sent. So at most the client will be able to decrypt 40 of their own bundles.
    private static final int MAX_PSI_ELEMENTS_PER_REQUEST = 40;
    private static final long PSI_SESSION_TTL_MS = 5 * 60 * 1000; // 5 minutes
    private static RpcServer rpcServerInstance;
    private final BundleTransportService bundleTransportService;
    private final ConcurrentHashMap<String, PSISession> psiSessions = new ConcurrentHashMap<>();
    private Server server;

    record PSISession(List<String> bundleFileNames, long createdAt) {}

    public RpcServer(BundleTransportService bundleTransportService) {
        this.bundleTransportService = bundleTransportService;
    }

    public void startServer(TransportPaths transportPaths) {
        if (server != null && !server.isShutdown()) {
            return;
        }
        var toServerPath = transportPaths.toServerPath;
        var toClientPath = transportPaths.toClientPath;
        var bundleExchangeService = new BundleExchangeServiceImpl() {
            @Override
            protected void onBundleExchangeEvent(BundleExchangeEvent bundleExchangeEvent) {
                bundleTransportService.onBundleExchangeEvent(bundleExchangeEvent);
            }

            @Override
            protected Path pathProducer(BundleExchangeName bundleExchangeName,
                                        BundleSenderType bundleSenderType,
                                        PublicKeyMap publicKeyMap) {
                if (bundleExchangeName.isDownload()) {
                    var path = toClientPath.resolve(bundleExchangeName.encryptedBundleId());
                    return Files.exists(path) ? path : null;
                } else {
                    return toServerPath.resolve(bundleExchangeName.encryptedBundleId());
                }
            }

            @Override
            protected void bundleCompletion(BundleExchangeName bundleExchangeName,
                                            BundleSenderType senderType,
                                            Path path) {
            }

            @Override
            public void getRecencyBlob(GetRecencyBlobRequest request,
                                       StreamObserver<GetRecencyBlobResponse> responseObserver) {
                var recencyBlobResponse = GetRecencyBlobResponse.getDefaultInstance();
                var recencyBlobPath = toClientPath.resolve(TransportToBundleServerManager.RECENCY_BLOB_BIN);
                try (var is = Files.newInputStream(recencyBlobPath)) {
                    recencyBlobResponse = GetRecencyBlobResponse.parseFrom(is);
                } catch (IOException e) {
                    logger.log(SEVERE, "Failed to read recency blob", e);
                }

                responseObserver.onNext(recencyBlobResponse);
                responseObserver.onCompleted();
            }

            @Override
            public void psiExchange(PSIRequest request, StreamObserver<PSIResponse> responseObserver) {
                onBundleExchangeEvent(BundleExchangeEvent.DOWNLOAD_STARTED);
                try {
                    int numElements = request.getClientBlindedValuesCount();
                    if (numElements > MAX_PSI_ELEMENTS_PER_REQUEST) {
                        responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT.withDescription(
                                "Too many PSI elements: " + numElements + ", maximum allowed: " +
                                        MAX_PSI_ELEMENTS_PER_REQUEST).asException());
                        return;
                    }
                    var psi = new BundleOwnershipPSI();
                    BigInteger transportSecret = psi.generateSecret();

                    List<String> bundleFileNames = listBundleFiles(toClientPath);

                    List<BigInteger> clientBlinded = request.getClientBlindedValuesList()
                            .stream()
                            .map(e -> new BigInteger(1, e.getValue().toByteArray()))
                            .collect(Collectors.toList());

                    var transportResponse = psi.transportProcess(clientBlinded, bundleFileNames, transportSecret);

                    String sessionId = UUID.randomUUID().toString();
                    psiSessions.put(sessionId, new PSISession(bundleFileNames, System.currentTimeMillis()));

                    // Clean up expired sessions
                    long cutoff = System.currentTimeMillis() - PSI_SESSION_TTL_MS;
                    psiSessions.entrySet().removeIf(e -> e.getValue().createdAt() < cutoff);

                    var responseBuilder = PSIResponse.newBuilder().setSessionId(sessionId);
                    for (BigInteger val : transportResponse.doublyBlindedClientValues()) {
                        responseBuilder.addDoublyBlindedClientValues(PSIElement.newBuilder()
                                                                             .setValue(ByteString.copyFrom(val.toByteArray()))
                                                                             .build());
                    }
                    for (BigInteger val : transportResponse.transportBlindedValues()) {
                        responseBuilder.addTransportBlindedValues(PSIElement.newBuilder()
                                                                          .setValue(ByteString.copyFrom(val.toByteArray()))
                                                                          .build());
                    }

                    responseObserver.onNext(responseBuilder.build());
                    responseObserver.onCompleted();
                } catch (Exception e) {
                    logger.log(SEVERE, "PSI exchange failed", e);
                    responseObserver.onError(io.grpc.Status.INTERNAL.withDescription(e.getMessage()).asException());
                } finally {
                    onBundleExchangeEvent(BundleExchangeEvent.DOWNLOAD_FINISHED);
                }
            }

            @Override
            public void psiDownloadBundle(PSIDownloadRequest request,
                                          StreamObserver<BundleDownloadResponse> responseObserver) {
                onBundleExchangeEvent(BundleExchangeEvent.DOWNLOAD_STARTED);
                try {
                    PSISession session = psiSessions.get(request.getSessionId());
                    if (session == null || System.currentTimeMillis() - session.createdAt() > PSI_SESSION_TTL_MS) {
                        if (session != null) {
                            psiSessions.remove(request.getSessionId());
                        }
                        responseObserver.onError(io.grpc.Status.NOT_FOUND.withDescription(
                                "PSI session expired or not found").asException());
                        return;
                    }
                    int index = request.getTransportIndex();
                    if (index < 0 || index >= session.bundleFileNames().size()) {
                        responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT.withDescription(
                                "Invalid transport index").asException());
                        return;
                    }
                    String fileName = session.bundleFileNames().get(index);
                    Path filePath = toClientPath.resolve(fileName);

                    if (!Files.exists(filePath)) {
                        responseObserver.onError(io.grpc.Status.NOT_FOUND.withDescription("Bundle file not found")
                                                         .asException());
                        return;
                    }

                    try (InputStream is = Files.newInputStream(filePath, StandardOpenOption.READ)) {
                        transferToStream(is,
                                         bytes -> responseObserver.onNext(BundleDownloadResponse.newBuilder()
                                                                                  .setChunk(BundleChunk.newBuilder()
                                                                                                    .setChunk(bytes)
                                                                                                    .build())
                                                                                  .build()));
                    }
                    responseObserver.onCompleted();
                    psiSessions.remove(request.getSessionId());
                } catch (Exception e) {
                    logger.log(SEVERE, "PSI download failed", e);
                    responseObserver.onError(io.grpc.Status.INTERNAL.withDescription(e.getMessage()).asException());
                } finally {
                    onBundleExchangeEvent(BundleExchangeEvent.DOWNLOAD_FINISHED);
                }
            }

            private List<String> listBundleFiles(Path dir) {
                File[] files = dir.toFile().listFiles();
                if (files == null) return List.of();
                return Arrays.stream(files)
                        .filter(f -> f.isFile() && !f.getName().equals(TransportToBundleServerManager.RECENCY_BLOB_BIN))
                        .map(File::getName)
                        .collect(Collectors.toList());
            }
        };

        try {
            KeyManager[] keyManagers = DDDTLSUtil.getKeyManagerFactory(bundleTransportService.grpcKeys.grpcKeyPair,
                                                                       bundleTransportService.grpcKeys.grpcCert)
                    .getKeyManagers();
            var credentials = TlsServerCredentials.newBuilder().keyManager(keyManagers).build();
            server = OkHttpServerBuilder.forPort(port, credentials).maxInboundMessageSize(20 * 1024 * 1024) // 20 MB;
                    .addService(bundleExchangeService).executor(Executors.newFixedThreadPool(4)).build();
        } catch (Exception e) {
            logger.log(SEVERE, "TLS communication exceptions ", e);
            return;
        }
        logger.log(INFO, "Starting rpc server at: " + server.toString());

        try {
            server.start();
            logger.log(FINE, "Rpc server running at: " + server.toString());
        } catch (IOException e) {
            logger.log(WARNING, "RpcServer -> startServer() IOException: " + e.getMessage());
        }
    }

    public void shutdownServer() {
        if (server != null && server.isShutdown()) {
            return;
        }

        logger.log(INFO, "Stopping rpc server");
        if (server != null) {
            try {
                boolean stopped = server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                if (!stopped) {
                    throw new Exception("Server not stopped");
                }
                logger.log(WARNING, "Stopped rpc server");
            } catch (IOException e) {
                logger.log(WARNING, "RpcServer -> terminateServer() IOException: " + e.getMessage());
            } catch (Exception e) {
                logger.log(WARNING, "RpcServer -> terminateServer() Exception: " + e.getMessage());
            }
        }
    }

    public boolean isServerRunning() {
        return server != null && !server.isShutdown();
    }

    public boolean isShutdown() {
        if (server == null) {
            return true;
        }
        return server.isShutdown();
    }
}
