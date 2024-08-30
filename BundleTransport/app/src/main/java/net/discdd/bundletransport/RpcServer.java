package net.discdd.bundletransport;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import android.content.Context;

import net.discdd.bundlerouting.service.BundleExchangeServiceImpl;
import net.discdd.grpc.BundleSender;
import net.discdd.grpc.GetRecencyBlobRequest;
import net.discdd.grpc.GetRecencyBlobResponse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;

public class RpcServer {
    public enum ServerState {
        RUNNING, PENDING, SHUTDOWN
    }

    private static final Logger logger = Logger.getLogger(RpcServer.class.getName());

    // private final String TAG = "dddTransport";
    private static final int port = 7777;
    private ServerState state = ServerState.SHUTDOWN;

    private Server server;
    private static RpcServer rpcServerInstance;

    private final BundleExchangeServiceImpl.BundleExchangeEventListener listener;

    public RpcServer(BundleExchangeServiceImpl.BundleExchangeEventListener listener) {
        this.listener = listener;
    }

    public void startServer(Context context) {
        logger.log(INFO, "Server state is : " + state.name());
        if (state == ServerState.RUNNING || state == ServerState.PENDING) {
            return;
        }
        state = ServerState.PENDING;
        var toServerPath = context.getExternalFilesDir(null).toPath().resolve("BundleTransmission/server");
        var toClientPath = context.getExternalFilesDir(null).toPath().resolve("BundleTransmission/client");
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
            protected void bundleCompletion(BundleExchangeName bundleExchangeName) {
            }

            @Override
            public void getRecencyBlob(GetRecencyBlobRequest request,
                                       StreamObserver<GetRecencyBlobResponse> responseObserver) {
                var recencyBlobResponse = GetRecencyBlobResponse.getDefaultInstance();
                try (var is = Files.newInputStream(
                        toClientPath.resolve(TransportToBundleServerManager.RECENCY_BLOB_BIN))) {
                    recencyBlobResponse = GetRecencyBlobResponse.parseFrom(is);
                } catch (IOException e) {
                    logger.log(SEVERE, "Failed to read recency blob", e);
                }

                responseObserver.onNext(recencyBlobResponse);
                responseObserver.onCompleted();
            }
        };
        server = NettyServerBuilder.forPort(7777).addService(bundleExchangeService).build();

        logger.log(INFO, "Starting rpc server at: " + server.toString());

        try {
            server.start();
            state = ServerState.RUNNING;
            logger.log(FINE, "Rpc server running at: " + server.toString());
        } catch (IOException e) {
            state = ServerState.SHUTDOWN;
            logger.log(WARNING, "RpcServer -> startServer() IOException: " + e.getMessage());
        }
    }

    public void shutdownServer() {
        if (state == ServerState.SHUTDOWN || state == ServerState.PENDING) {
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

    public ServerState getState() {
        return state;
    }

    public boolean isShutdown() {
        if (server == null) {
            return true;
        }
        return server.isShutdown();
    }
}
