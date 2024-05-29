package com.ddd.server;

import com.ddd.server.commands.CommandProcessor;
import com.ddd.server.commands.bundleuploader.BundleUploader;
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

@Slf4j
@SpringBootApplication
@EnableJpaRepositories("com.ddd.server")
@EntityScan("com.ddd.server.repository.entity")
public class BundleServerApplication {

    public static void main(String[] args) {
        class MySpringApplication extends SpringApplication {
            MySpringApplication(Class<?>... primarySources) {
                super(primarySources);
            }

            @Override
            protected void refresh(ConfigurableApplicationContext applicationContext) {
                // check for obvious configuration errors
                var root = applicationContext.getEnvironment().getProperty("bundle-server.bundle-store-root");
                if (root == null || !new File(root).isDirectory()) {
                    log.error("bundle-server.bundle-store-root is not a directory or not set: " + root);
                    System.exit(1);
                }
                super.refresh(applicationContext);
            }
        }
        var app = new MySpringApplication(BundleServerApplication.class);
        if (CommandProcessor.checkForCommand(args)) {
            // we are doing a CLI command, so don't start up like a server
            app.setWebApplicationType(WebApplicationType.NONE);
            app.setBannerMode(Banner.Mode.OFF);
            app.setLogStartupInfo(false);
        }
        new BundleUploader().run(args);

        if (args.length > 0) {
            Resource resource = new FileSystemResource(args[0]);
            if (resource.exists()){
                try {
                    Properties properties = PropertiesLoaderUtils.loadProperties(resource);
                    app.setDefaultProperties(properties);
                    args = Arrays.copyOfRange(args, 1, args.length);
                } catch (Exception e){
                    log.error("Please enter valid properties file path!");
                    System.exit(1);
                }
            }else {
                log.error("Entered properties file path does not exist!");
                System.exit(1);
            }
        }else {
            log.error("Please enter properties file path as argument!");
            System.exit(1);
        }

        app.run(args);
    }
}