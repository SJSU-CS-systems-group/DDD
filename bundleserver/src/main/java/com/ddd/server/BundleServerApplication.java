package com.ddd.server;

import com.ddd.server.commands.CommandProcessor;
import com.ddd.server.commands.bundleuploader.BundleUploader;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.io.File;

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
                    System.err.println("bundle-server.bundle-store-root is not a directory or not set: " + root);
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
        app.run(args);
    }
}