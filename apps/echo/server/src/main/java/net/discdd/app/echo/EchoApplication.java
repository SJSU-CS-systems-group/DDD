package net.discdd.app.echo;

import net.discdd.config.GrpcSecurityConfig;
import net.discdd.grpc.GrpcServerRunner;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Properties;
import java.util.logging.Logger;

/*
 * This is the echo ServiceAdapter. It is just for testing. It works with the echo client app which will
 * generate a message and this adapter will send it back with " was received" appended to it.
 * The EchoDDDAdapter is the real implementation of the echo service and is auto-magically registered using
 * the GrpcService annotation.
 */
@SpringBootApplication
@Import({ GrpcServerRunner.class, GrpcSecurityConfig.class })
public class EchoApplication {
    final static Logger logger = Logger.getLogger(EchoApplication.class.getName());

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Usage: java -jar jar_file_path.jar BundleServerURL");
            System.exit(1);
        }

        var app = new SpringApplication(EchoApplication.class);

        var properties = new Properties();
        properties.load(new FileInputStream(args[0]));

        app.setDefaultProperties(properties);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);

        // now start the app skipping the bundleServerURL argument
        app.run(Arrays.copyOfRange(args, 1, args.length));
    }
}
