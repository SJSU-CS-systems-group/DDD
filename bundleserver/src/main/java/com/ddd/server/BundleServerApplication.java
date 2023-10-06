package com.ddd.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BundleServerApplication {
  @Value("${bundle-server.bundle-transmission.encrypted-payload-directory}")
  void setFoo(String val) {
    System.out.println("********* in /server/BundleServerApplication, encrypted-payload-directory: "+val);
  }

  public static void main(String[] args) {
    SpringApplication.run(BundleServerApplication.class, args);
      //  DTNBundleServer.begin();
  }
}
