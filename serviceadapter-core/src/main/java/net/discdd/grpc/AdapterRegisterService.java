package net.discdd.grpc;

import io.grpc.ConnectivityState;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.handler.ssl.SslContext;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.tls.DDDTLSUtil;
import net.discdd.tls.GrpcSecurity;
import net.discdd.tls.NettyClientCertificateInterceptor;
import org.bouncycastle.operator.OperatorCreationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.whispersystems.libsignal.InvalidKeyException;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.nio.file.Path;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.cert.CertificateException;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

@Service
public class AdapterRegisterService {
    private static final Logger logger = Logger.getLogger(AdapterRegisterService.class.getName());

    public AdapterRegisterService(@Value("${bundle-server.url}") String bundleServerURL,
                                  @Value("${my.grpc.url}") String myGrpcUrl,
                                  @Value("${spring.application.name}") String appName,
                                  @Value("${adapter-server.root-dir}") String rootDir) {
        registerWithBundleServer(bundleServerURL, myGrpcUrl, rootDir, appName);
    }

    public static void registerWithBundleServer(String bundleServerURL, String myGrpcUrl, String rootDir, String appName) {
        if (myGrpcUrl == null) {
            logger.log(SEVERE, "my.grpc.url is not set in application.properties");
            System.exit(1);
        }

        GrpcSecurity grpcSecurity;
        try {
            grpcSecurity = GrpcSecurity.getInstance(Path.of(rootDir, SecurityUtils.GRPC_SECURITY_PATH), SecurityUtils.SERVER);
        } catch (IOException | NoSuchAlgorithmException | InvalidAlgorithmParameterException |
                 CertificateException | NoSuchProviderException | InvalidKeyException | OperatorCreationException e) {
            logger.log(SEVERE, "Failed to initialize GrpcSecurity", e);
            return;
        }

        SslContext sslClientContext;
        try {
            sslClientContext = GrpcSslContexts.forClient()
                    .keyManager(grpcSecurity.getGrpcKeyPair().getPrivate(), grpcSecurity.getGrpcCert())
                    .trustManager(DDDTLSUtil.trustManager)
                    .build();
        } catch (SSLException e) {
            logger.log(SEVERE, "Could not create SSL context: " + e.getMessage());
            System.exit(1);
            return;
        }

        var managedChannel = NettyChannelBuilder.forTarget(bundleServerURL)
                .sslContext(sslClientContext)
                .intercept(new NettyClientCertificateInterceptor())
                .useTransportSecurity()
                .build();

        try {
            ConnectivityState channelState = managedChannel.getState(true);
            if (channelState != ConnectivityState.READY) {
                logger.log(WARNING, "Could not connect to " + bundleServerURL + " " + channelState);
            } else {
                var rsp = ServiceAdapterRegistryServiceGrpc.newBlockingStub(managedChannel)
                        .checkAdapterRegistration(ConnectionData.newBuilder().setAppName(appName).setUrl(myGrpcUrl).build());

                if (rsp.getCode() != 0) {
                    logger.log(WARNING, "Could not register with BundleServer: rc = " + rsp.getCode() + " " + rsp.getMessage());
                } else {
                    logger.log(INFO, "Registered with server at " + bundleServerURL);
                }
            }
        } catch (Exception e) {
            logger.log(WARNING, "Could not register with BundleServer: " + e.getMessage());
        } finally {
            managedChannel.shutdown();
        }
    }
}
