package net.discdd.server;

import static java.util.logging.Level.SEVERE;

import java.io.File;
import java.util.Arrays;
import java.util.logging.Logger;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import net.discdd.config.GrpcSecurityConfig;
import net.discdd.grpc.GrpcServerRunner;
import net.discdd.server.commands.CommandProcessor;
import net.discdd.server.commands.bundleuploader.BundleUploader;

import io.leego.banana.Ansi;
import io.leego.banana.BananaUtils;
import io.leego.banana.Font;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBootApplication
@Import({ GrpcServerRunner.class, GrpcSecurityConfig.class })
@EnableJpaRepositories("net.discdd.server")
@EntityScan("net.discdd.server.repository.entity")
public class BundleServerApplication {
    private static final Logger logger = Logger.getLogger(BundleServerApplication.class.getName());

    public static void main(String[] args) {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%5$s%6$s%n");

        class MySpringApplication extends SpringApplication {
            MySpringApplication(Class<?>... primarySources) {
                super(primarySources);
            }

            @Override
            protected void refresh(ConfigurableApplicationContext applicationContext) {
                // check for obvious configuration errors
                var root = applicationContext.getEnvironment().getProperty("bundle-server.bundle-store-root");
                if (root != null && !root.endsWith("/")) {
                    root += "/";
                    System.setProperty("bundle-server.bundle-store-root", root);
                }

                if (root == null || !new File(root).isDirectory()) {
                    logger.log(SEVERE, "bundle-server.bundle-store-root is not a directory or not set: " + root);
                    System.exit(1);
                }
                super.refresh(applicationContext);
            }
        }

        var app = new MySpringApplication(BundleServerApplication.class);

        if (args.length == 0) {
            logger.log(SEVERE, "Please enter properties file path as argument!");
            System.exit(1);
        }

        Resource resource = new FileSystemResource(args[0]);
        if (!resource.exists()) {
            logger.log(SEVERE, String.format("Entered properties file path %s does not exist!", args[0]));
            System.exit(1);
        }

        try {
            var properties = PropertiesLoaderUtils.loadProperties(resource);
            properties.forEach((key, value) -> System.setProperty(key.toString(), value.toString()));
            args = Arrays.copyOfRange(args, 1, args.length);
        } catch (Exception e) {
            logger.log(SEVERE, "Please enter valid properties file path!");
            System.exit(1);
        }

        app.setBanner((e, s, o) -> o.println(BananaUtils.bananansi("DDD Bundle Server", Font.ANSI_SHADOW, Ansi.GREEN)));

        if (CommandProcessor.checkForCommand(args)) {
            // we are doing a CLI command, so don't start up like a server
            app.setWebApplicationType(WebApplicationType.NONE);
            app.setBannerMode(Banner.Mode.OFF);
            app.setLogStartupInfo(false);
            System.setProperty("logging.level.root", "WARN");
            System.setProperty("ssl-grpc.server.port", "-1");
            System.setProperty("logging.pattern.console", "%-1level %msg%n");
        }

        new BundleUploader().run(args);

        app.run(args);
    }
}
