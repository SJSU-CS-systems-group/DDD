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

import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

/*
 * This is the echo ServiceAdapter. It is just for testing. It works with the echo client app which will
 * generate a message and this adapter will send it back with " was received" appended to it.
 * The EchoDDDAdapter is the real implementation of the echo service and is auto-magically registered using
 * the GrpcService annotation.
 */
@SpringBootApplication
public class EchoApplication {
    final static Logger logger = Logger.getLogger(EchoApplication.class.getName());

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java -jar jar_file_path.jar BundleServerURL");
            System.exit(1);
        }

        var app = new SpringApplication(EchoApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        // we need to register with the BundleServer in an application initializer so that
        // the logging will be set up correctly
        app.addInitializers((actx) -> {
            var bundleServerURL = args[0];
            var myGrpcUrl = actx.getEnvironment().getProperty("my.grpc.url");
            if (myGrpcUrl == null) {
                logger.log(SEVERE, "my.grpc.url is not set in application.properties");
                System.exit(1);
            }
            var managedChannel = ManagedChannelBuilder.forTarget(bundleServerURL).usePlaintext().build();
            var channelState = managedChannel.getState(true);
            try {
                if (channelState != ConnectivityState.READY) {
                    logger.log(WARNING, String.format("Could not connect to %s %s", bundleServerURL, channelState));
                } else {
                    var rsp = ServiceAdapterRegistryGrpc.newBlockingStub(managedChannel)
                            .registerAdapter(ConnectionData.newBuilder().setAppName("echo").setUrl(myGrpcUrl).build());
                    if (rsp.getCode() != 0) {
                        logger.log(WARNING,
                                   String.format("Could not register with BundleServer: rc = %d %s", rsp.getCode(),
                                                 rsp.getMessage()));
                    }
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
