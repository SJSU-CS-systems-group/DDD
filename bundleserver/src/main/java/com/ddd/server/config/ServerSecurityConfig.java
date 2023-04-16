package com.ddd.server.config;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.ddd.server.bundlesecurity.ServerSecurity;

@Configuration
public class ServerSecurityConfig {
  
  @Autowired private BundleServerConfig bundleStoreConfig;
  
  @Bean ServerSecurity createServerSecurityInstance() {
    try {
      return ServerSecurity.getInstance(bundleStoreConfig.getBundleSecurity().getServerKeyPath());
    } catch (NoSuchAlgorithmException e) {
      // TODO 
      e.printStackTrace();
    } catch (IOException e) {
      // TODO 
      e.printStackTrace();
    }
    return null;
  }
}
