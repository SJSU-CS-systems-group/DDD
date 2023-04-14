package com.ddd.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BundleServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(BundleServerApplication.class, args);
    DTNBundleServer.begin();
  }
}
