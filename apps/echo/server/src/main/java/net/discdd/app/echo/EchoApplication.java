package net.discdd.app.echo;

import io.grpc.ConnectivityState;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.handler.ssl.SslContext;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.grpc.ConnectionData;
import net.discdd.grpc.GrpcServerRunner;
import net.discdd.grpc.ServiceAdapterRegistryServiceGrpc;
import net.discdd.tls.DDDTLSUtil;
import net.discdd.tls.GrpcSecurity;
import net.discdd.tls.NettyClientCertificateInterceptor;
import org.bouncycastle.operator.OperatorCreationException;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.whispersystems.libsignal.InvalidKeyException;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.nio.file.Path;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

/*
 * This is the echo ServiceAdapter. It is just for testing. It works with the echo client app which will
 * generate a message and this adapter will send it back with " was received" appended to it.
 * The EchoDDDAdapter is the real implementation of the echo service and is auto-magically registered using
 * the GrpcService annotation.
 */
@SpringBootApplication
@Import(GrpcServerRunner.class)
public class EchoApplication {
    final static Logger logger = Logger.getLogger(EchoApplication.class.getName());

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java -jar jar_file_path.jar BundleServerURL");
            System.exit(1);
        }

        var app = new SpringApplication(EchoApplication.class);

        Resource resource = new FileSystemResource(args[0]);
        if (!resource.exists()) {
            logger.log(SEVERE, String.format("Entered properties file path %s does not exist!", args[0]));
            System.exit(1);
        }

        try {
            var properties = PropertiesLoaderUtils.loadProperties(resource);
            app.setDefaultProperties(properties);
        } catch (Exception e) {
            logger.log(SEVERE, "Please enter valid properties file path!");
            System.exit(1);
        }

        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        // we need to register with the BundleServer in an application initializer so that
        // the logging will be set up correctly
        app.addInitializers((actx) -> {
            var bundleServerURL = actx.getEnvironment().getProperty("bundle-server.url");
            var myGrpcUrl = actx.getEnvironment().getProperty("my.grpc.url");
            if (myGrpcUrl == null) {
                logger.log(SEVERE, "my.grpc.url is not set in application.properties");
                System.exit(1);
            }

            GrpcSecurity grpcSecurity = null;

            try {
                grpcSecurity = GrpcSecurity.getInstance(Path.of(actx.getEnvironment().getProperty("echo-server.root-dir")), SecurityUtils.ADAPTER);
            } catch (IOException | NoSuchAlgorithmException | InvalidAlgorithmParameterException |
                     CertificateException | NoSuchProviderException | InvalidKeyException |  OperatorCreationException e) {
                logger.log(SEVERE, "Failed to initialize GrpcSecurity for transport", e);
            }

            SslContext sslClientContext = null;
            try {
                sslClientContext = GrpcSslContexts.forClient()
                        .keyManager(grpcSecurity.getGrpcKeyPair().getPrivate(), grpcSecurity.getGrpcCert())
                        .trustManager(DDDTLSUtil.trustManager)
                        .build();
            } catch (SSLException e) {
                logger.log(SEVERE, "Could not create SSL context: " + e.getMessage());
                System.exit(1);
            }
            var managedChannel = NettyChannelBuilder.forTarget(bundleServerURL)
                    .sslContext(sslClientContext)
                    .intercept(new NettyClientCertificateInterceptor())
                    .useTransportSecurity()
                    .build();

            var channelState = managedChannel.getState(true);
            try {
                // TODO: remove the false when we figure out that the connect is successful!
                if (false && channelState != ConnectivityState.READY) {
                    logger.log(WARNING, String.format("Could not connect to %s %s", bundleServerURL, channelState));
                } else {
                    var rsp = ServiceAdapterRegistryServiceGrpc.newBlockingStub(managedChannel)
                            .withDeadlineAfter(5, TimeUnit.SECONDS).checkAdapterRegistration(
                                    ConnectionData.newBuilder().setAppName("echo").setUrl(myGrpcUrl).build());
                    if (rsp.getCode() != 0) {
                        logger.log(WARNING,
                                   String.format("Could not register with BundleServer: rc = %d %s", rsp.getCode(),
                                                 rsp.getMessage()));
                    }
                    logger.log(INFO, String.format("Registered with server at %s", bundleServerURL));
                }
            } catch (Exception e) {
                logger.log(WARNING, "Could not register with BundleServer: " + e.getMessage());
            } finally {
                managedChannel.shutdown();
            }
        });

        // now start the app skipping the bundleServerURL argument
        app.run(Arrays.copyOfRange(args, 1, args.length));
    }
}
