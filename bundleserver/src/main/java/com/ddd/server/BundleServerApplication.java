package com.ddd.server;

import com.ddd.server.commands.CommandProcessor;
import com.ddd.server.commands.bundleuploader.BundleUploader;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories("com.ddd.server")
@EntityScan("com.ddd.server.repository.entity")
public class BundleServerApplication {

  public static void main(String[] args) {
    var app = new SpringApplication(BundleServerApplication.class);
    if (CommandProcessor.checkForCommand(args)) {
      System.out.println("Disabling stuff!");
      app.setWebApplicationType(WebApplicationType.NONE);
      app.setBannerMode(Banner.Mode.OFF);
      app.setLogStartupInfo(false);
    }
    new BundleUploader().run(args);
    app.run(args);
  }
}
// Find who instantiate the DTNCommGrpc -> should have mysql object