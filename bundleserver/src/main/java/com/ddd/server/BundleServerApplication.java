package com.ddd.server;

import com.ddd.server.commands.CommandProcessor;
import com.ddd.server.commands.bundleuploader.BundleUploader;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;

@Slf4j
@SpringBootApplication
@EnableJpaRepositories("com.ddd.server")
@EntityScan("com.ddd.server.repository.entity")
public class BundleServerApplication {
    private static final Logger logger = Logger.getLogger(BundleServerApplication.class.getName());

    public static void main(String[] args) throws IOException {
        class MySpringApplication extends SpringApplication {
            MySpringApplication(Class<?>... primarySources) {
                super(primarySources);
            }

            @Override
            protected void refresh(ConfigurableApplicationContext applicationContext) {
                // check for obvious configuration errors
                var root = applicationContext.getEnvironment().getProperty("bundle-server.bundle-store-root");
                if (root == null || !new File(root).isDirectory()) {
                    logger.log(SEVERE, "bundle-server.bundle-store-root is not a directory or not set: " + root);
                    System.exit(1);
                }
                super.refresh(applicationContext);
            }
        }
        var app = new MySpringApplication(BundleServerApplication.class);

        if (!new ClassPathResource("custom-application.properties").exists()) {
            Files.createFile(Paths.get("src/main/resources/custom-application.properties"));
        }
        Resource resource = new ClassPathResource("custom-application.properties");
        if (resource.exists()) {
            try {
                Properties properties = PropertiesLoaderUtils.loadProperties(resource);
                app.setDefaultProperties(properties);
            } catch (Exception e) {
                logger.log(SEVERE, "Please enter valid properties file path!");
                System.exit(1);
            }
        } else {
            logger.log(SEVERE, "Entered properties file path does not exist!");
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
