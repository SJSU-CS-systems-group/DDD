package com.ddd.server.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.ddd.server.bundlesecurity.SecurityExceptions.ServerIntializationException;
import com.ddd.server.bundlesecurity.ServerSecurity;

@Configuration
public class ServerSecurityConfig {
  
  @Autowired private BundleServerConfig bundleStoreConfig;
  
  @Bean ServerSecurity createServerSecurityInstance() {
    try {
      System.out.println("Try " + bundleStoreConfig.getBundleSecurity().getServerKeyPath());

      return ServerSecurity.getInstance(bundleStoreConfig.getBundleSecurity().getServerKeyPath());
    } catch (Exception e) {
      // TODO Auto-generated catch block
      System.out.println("Catch " + bundleStoreConfig.getBundleSecurity().getServerKeyPath());
      e.printStackTrace();
    }
    return null;
  }
}
