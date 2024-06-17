package com.ddd.server.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.ddd.server.bundlesecurity.SecurityExceptions.ServerIntializationException;
import com.ddd.server.bundlesecurity.ServerSecurity;

import java.util.logging.Logger;

import static java.util.logging.Level.*;

@Configuration
public class ServerSecurityConfig {
    private static final Logger logger = Logger.getLogger(ServerSecurityConfig.class.getName());

    @Autowired
    private BundleServerConfig bundleStoreConfig;

    @Bean
    ServerSecurity createServerSecurityInstance() {
        try {
            logger.log(SEVERE, "Try " + bundleStoreConfig.getBundleSecurity().getServerKeyPath());

            return ServerSecurity.getInstance(bundleStoreConfig.getBundleSecurity().getServerKeyPath());
        } catch (Exception e) {
            // TODO Auto-generated catch block
            logger.log(SEVERE, "Catch " + bundleStoreConfig.getBundleSecurity().getServerKeyPath());
            e.printStackTrace();
        }
        return null;
    }
}
