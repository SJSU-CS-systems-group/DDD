package net.discdd.app.k9;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannelBuilder;
import net.discdd.grpc.ConnectionData;
import net.discdd.grpc.GrpcServerRunner;
import net.discdd.grpc.ServiceAdapterRegistryServiceGrpc;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.util.Arrays;
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
public class K9Application {
    final static Logger logger = Logger.getLogger(K9Application.class.getName());

    public static void main(final String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java -jar jar_file_path.jar BundleServerURL");
            System.exit(1);
        }

        var app = new SpringApplication(K9Application.class);

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

        app.setBannerMode(Banner.Mode.OFF);
        // we need to register with the BundleServer in an application initializer so that
        // the logging will be set up correctly
        app.addInitializers((actx) -> {
            var myGrpcUrl = actx.getEnvironment().getProperty("my.grpc.url");
            var appName = actx.getEnvironment().getProperty("spring.application.name");
            if (myGrpcUrl == null) {
                logger.log(SEVERE, "my.grpc.url is not set in application.properties");
                System.exit(1);
            }
            var managedChannel = ManagedChannelBuilder.forTarget(myGrpcUrl).usePlaintext().build();
            var channelState = managedChannel.getState(true);
            try {
                // TODO: remove the false when we figure out that the connect is successful!
                if (false && channelState != ConnectivityState.READY) {
                    logger.log(WARNING, String.format("Could not connect to %s %s", myGrpcUrl, channelState));
                } else {
                    var rsp = ServiceAdapterRegistryServiceGrpc.newBlockingStub(managedChannel)
                            .checkAdapterRegistration(
                                    ConnectionData.newBuilder().setAppName(appName).setUrl(myGrpcUrl).build());
                    if (rsp.getCode() != 0) {
                        logger.log(WARNING,
                                   String.format("Could not register with BundleServer: rc = %d %s", rsp.getCode(),
                                                 rsp.getMessage()));
                    }
                    logger.log(INFO, String.format("Registered with server at %s", myGrpcUrl));
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
