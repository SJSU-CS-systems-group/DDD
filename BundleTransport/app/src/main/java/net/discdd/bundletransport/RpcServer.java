package net.discdd.bundletransport;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import net.discdd.bundlerouting.service.BundleExchangeServiceImpl;
import net.discdd.grpc.BundleSender;
import net.discdd.grpc.GetRecencyBlobRequest;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.pathutils.TransportPaths;
import net.discdd.tls.DDDNettyTLS;
import net.discdd.tls.DDDTLSUtil;
import net.discdd.transport.TransportSecurity;
import net.discdd.transport.TransportToBundleServerManager;

import org.bouncycastle.operator.OperatorCreationException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import io.grpc.Server;
import io.grpc.stub.StreamObserver;

public class RpcServer {
    private static final Logger logger = Logger.getLogger(RpcServer.class.getName());

    // private final String TAG = "dddTransport";
    private static final int port = 7777;

    private Server server;
    private static RpcServer rpcServerInstance;
    private TransportSecurity transportSecurity;

    private final BundleExchangeServiceImpl.BundleExchangeEventListener listener;

    public RpcServer(BundleExchangeServiceImpl.BundleExchangeEventListener listener) {
        this.listener = listener;
    }

    public void startServer(TransportPaths transportPaths) {
        if (server != null && !server.isShutdown()) {
            return;
        }
        var toServerPath = transportPaths.toServerPath;
        var toClientPath = transportPaths.toClientPath;
        try {
            this.transportSecurity = new TransportSecurity(transportPaths);
        } catch (IOException | InvalidAlgorithmParameterException | NoSuchProviderException |
                 OperatorCreationException | NoSuchAlgorithmException | CertificateException e) {
            logger.log(SEVERE, "TransportSecurity exception ", e);
        }
        var bundleExchangeService = new BundleExchangeServiceImpl() {
            @Override
            protected void onBundleExchangeEvent(BundleExchangeEvent bundleExchangeEvent) {
                listener.onBundleExchangeEvent(bundleExchangeEvent);
            }

            @Override
            protected Path pathProducer(BundleExchangeName bundleExchangeName, BundleSender bundleSender) {
                return bundleExchangeName.isDownload() ? toClientPath.resolve(bundleExchangeName.encryptedBundleId()) :
                        toServerPath.resolve(bundleExchangeName.encryptedBundleId());
            }

            @Override
            protected void bundleCompletion(BundleExchangeName bundleExchangeName, BundleSender sender, Path path) {
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
            var sslContext = SSLContext.getInstance("TLS");
            sslContext.init(
                    DDDTLSUtil.getKeyManagerFactory(transportSecurity.getTransportKeyPair(),
                                                    transportSecurity.getTransportCert()).getKeyManagers(),
                    new TrustManager[] {DDDTLSUtil.trustManager},
                    new SecureRandom()
            );

            server = DDDNettyTLS.createGrpcServer(
                    transportSecurity.getTransportKeyPair(),
                    transportSecurity.getTransportCert(),
                    7777,
                    bundleExchangeService
            );
        } catch (Exception e) {
            logger.log(SEVERE, "TLS communication exceptions ", e);
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
