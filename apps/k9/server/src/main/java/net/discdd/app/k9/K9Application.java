package net.discdd.app.k9;

import net.discdd.config.GrpcSecurityConfig;
import net.discdd.grpc.AdapterRegisterService;
import net.discdd.grpc.GrpcServerRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/*
 * This is the echo ServiceAdapter. It is just for testing. It works with the echo client app which will
 * generate a message and this adapter will send it back with " was received" appended to it.
 * The EchoDDDAdapter is the real implementation of the echo service and is auto-magically registered using
 * the GrpcService annotation.
 */
@SpringBootApplication
@Import({ GrpcServerRunner.class, GrpcSecurityConfig.class, AdapterRegisterService.class })
public class K9Application {
    final static Logger logger = Logger.getLogger(K9Application.class.getName());

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Usage: java -jar jar_file_path.jar BundleServerURL");
            System.exit(1);
        }

        var app = new SpringApplication(K9Application.class);

        var properties = new Properties();
        properties.load(new FileInputStream(args[0]));

        app.setDefaultProperties(properties);
        app.setBannerMode(Banner.Mode.OFF);

        // now start the app skipping the bundleServerURL argument
        app.run(Arrays.copyOfRange(args, 1, args.length));
    }
}
