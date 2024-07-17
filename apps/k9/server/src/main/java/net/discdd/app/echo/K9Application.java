package net.discdd.app.echo;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannelBuilder;
import net.discdd.server.ConnectionData;
import net.discdd.server.ServiceAdapterRegistryGrpc;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

/*
 * This is the K9 ServiceAdapter.It works with the k9 client app.
 */
@SpringBootApplication
public class K9Application {
    final static Logger logger = Logger.getLogger(K9Application.class.getName());

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java -jar jar_file_path.jar BundleServerURL");
            System.exit(1);
        }

        var app = new SpringApplication(K9Application.class);
        app.setBannerMode(Banner.Mode.OFF);
        // we need to register with the BundleServer in an application initializer so that
        // the logging will be set up correctly
        app.addInitializers((actx) -> {
            var bundleServerURL = args[0];
            var myGrpcUrl = actx.getEnvironment().getProperty("my.grpc.url");
            var appName = actx.getEnvironment().getProperty("spring.application.name");
            if (myGrpcUrl == null) {
                logger.log(SEVERE, "my.grpc.url is not set in application.properties");
                System.exit(1);
            }
            var managedChannel = ManagedChannelBuilder.forTarget(bundleServerURL).usePlaintext().build();
            var channelState = managedChannel.getState(true);
            try {
                // TODO: remove the false when we figure out that the connect is successful!
                if (false && channelState != ConnectivityState.READY) {
                    logger.log(WARNING, String.format("Could not connect to %s %s", bundleServerURL, channelState));
                } else {
                    var rsp = ServiceAdapterRegistryGrpc.newBlockingStub(managedChannel)
                            .registerAdapter(ConnectionData.newBuilder().setAppName(appName).setUrl(myGrpcUrl).build());
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
