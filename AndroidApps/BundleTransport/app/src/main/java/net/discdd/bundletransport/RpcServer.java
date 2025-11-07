package net.discdd.bundletransport;

import io.grpc.Server;
import io.grpc.TlsServerCredentials;
import io.grpc.okhttp.OkHttpServerBuilder;
import io.grpc.stub.StreamObserver;
import net.discdd.bundlerouting.service.BundleExchangeServiceImpl;
import net.discdd.grpc.BundleSenderType;
import net.discdd.grpc.GetRecencyBlobRequest;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.PublicKeyMap;
import net.discdd.pathutils.TransportPaths;
import net.discdd.tls.DDDTLSUtil;
import net.discdd.tls.GrpcSecurityKey;
import net.discdd.transport.TransportToBundleServerManager;

import javax.net.ssl.KeyManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

public class RpcServer {
    private static final Logger logger = Logger.getLogger(RpcServer.class.getName());

    // private final String TAG = "dddTransport";
    private static final int port = 7777;
    private static RpcServer rpcServerInstance;
    private final BundleTransportService bundleTransportService;
    private Server server;

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
