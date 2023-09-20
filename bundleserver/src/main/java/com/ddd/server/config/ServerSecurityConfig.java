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
      return ServerSecurity.getInstance(bundleStoreConfig.getBundleSecurity().getServerKeyPath());
    } catch (ServerIntializationException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    return null;
  }
}
