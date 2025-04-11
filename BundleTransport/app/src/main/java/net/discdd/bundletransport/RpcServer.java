package net.discdd.bundletransport;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import net.discdd.bundlerouting.service.BundleExchangeServiceImpl;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.grpc.BundleSenderType;
import net.discdd.grpc.GetRecencyBlobRequest;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.PublicKeyMap;
import net.discdd.pathutils.TransportPaths;

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

import net.discdd.tls.DDDNettyTLS;
import net.discdd.tls.DDDTLSUtil;
import net.discdd.tls.NettyServerCertificateInterceptor;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.stub.StreamObserver;

import net.discdd.tls.GrpcSecurity;
import net.discdd.transport.GrpcSecurityHolder;
import net.discdd.transport.TransportToBundleServerManager;

import org.bouncycastle.operator.OperatorCreationException;
import org.whispersystems.libsignal.InvalidKeyException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

public class RpcServer {
    private static final Logger logger = Logger.getLogger(RpcServer.class.getName());

    // private final String TAG = "dddTransport";
    private static final int port = 7777;

    private Server server;
    private static RpcServer rpcServerInstance;
    private GrpcSecurity transportGrpcSecurity;

    private final BundleExchangeServiceImpl.BundleExchangeEventListener listener;

    public RpcServer(BundleExchangeServiceImpl.BundleExchangeEventListener listener) {
        this.listener = listener;
    }

    public void startServer(TransportPaths transportPaths) {
        if (server != null && !server.isShutdown()) {
            return;
        }

        this.transportGrpcSecurity = GrpcSecurityHolder.getGrpcSecurityHolder();

        var toServerPath = transportPaths.toServerPath;
        var toClientPath = transportPaths.toClientPath;
        var bundleExchangeService = new BundleExchangeServiceImpl() {
            @Override
            protected void onBundleExchangeEvent(BundleExchangeEvent bundleExchangeEvent) {
                listener.onBundleExchangeEvent(bundleExchangeEvent);
            }
            @Override
            protected Path pathProducer(BundleExchangeName bundleExchangeName, BundleSenderType bundleSenderType, PublicKeyMap publicKeyMap) {
                return bundleExchangeName.isDownload() ? toClientPath.resolve(bundleExchangeName.encryptedBundleId()) :
                        toServerPath.resolve(bundleExchangeName.encryptedBundleId());
            }
            @Override
            protected void bundleCompletion(BundleExchangeName bundleExchangeName, BundleSenderType senderType, Path path) {
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
                    DDDTLSUtil.getKeyManagerFactory(transportGrpcSecurity.getGrpcKeyPair(),
                                                    transportGrpcSecurity.getGrpcCert()).getKeyManagers(),
                    new TrustManager[] {DDDTLSUtil.trustManager},
                    new SecureRandom()
            );

            server = DDDNettyTLS.createGrpcServer(
                    transportGrpcSecurity.getGrpcKeyPair(),
                    transportGrpcSecurity.getGrpcCert(),
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
