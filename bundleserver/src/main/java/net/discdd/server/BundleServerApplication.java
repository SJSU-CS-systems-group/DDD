package net.discdd.server;

import net.discdd.server.commands.CommandProcessor;
import net.discdd.server.commands.bundleuploader.BundleUploader;
import io.leego.banana.Ansi;
import io.leego.banana.BananaUtils;
import io.leego.banana.Font;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.io.File;
import java.util.Arrays;
import java.util.Properties;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;

@Slf4j
@SpringBootApplication
@EnableJpaRepositories("net.discdd.server")
@EntityScan("net.discdd.server.repository.entity")
public class BundleServerApplication {
    private static final Logger logger = Logger.getLogger(BundleServerApplication.class.getName());

    public static void main(String[] args) {
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
        if (args.length > 0) {
            Resource resource = new FileSystemResource(args[0]);
            if (resource.exists()) {
                try {
                    Properties properties = PropertiesLoaderUtils.loadProperties(resource);
                    app.setDefaultProperties(properties);
                    args = Arrays.copyOfRange(args, 1, args.length);
                } catch (Exception e) {
                    logger.log(SEVERE, "Please enter valid properties file path!");
                    System.exit(1);
                }
            } else {
                logger.log(SEVERE, "Entered properties file path does not exist!");
                System.exit(1);
            }
        } else {
            logger.log(SEVERE, "Please enter properties file path as argument!");
            System.exit(1);
        }

        app.setBanner((e, s, o) -> o.println(BananaUtils.bananansi("DDD Bundle Server", Font.ANSI_SHADOW, Ansi.GREEN)));

        if (CommandProcessor.checkForCommand(args)) {
            // we are doing a CLI command, so don't start up like a server
            app.setWebApplicationType(WebApplicationType.NONE);
            app.setBannerMode(Banner.Mode.OFF);
            app.setLogStartupInfo(false);
        }
        new BundleUploader().run(args);

        app.run(args);
    }
}
