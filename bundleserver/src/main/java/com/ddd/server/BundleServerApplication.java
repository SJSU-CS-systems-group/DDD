package com.ddd.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories("com.ddd.server")
@ComponentScan("com.ddd.server")
@EntityScan("com.ddd.server.repository.entity")
public class BundleServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(BundleServerApplication.class, args);
      //  DTNBundleServer.begin();
  }
}
// Find who instantiate the DTNCommGrpc -> should have mysql object