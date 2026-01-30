package net.discdd.server.config;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.SEVERE;

import java.util.logging.Logger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import net.discdd.bundlesecurity.ServerSecurity;

@Configuration
public class ServerSecurityConfig {
    private static final Logger logger = Logger.getLogger(ServerSecurityConfig.class.getName());

    @Autowired
    private BundleServerConfig bundleStoreConfig;

    @Bean
    ServerSecurity createServerSecurityInstance() {
        try {
            logger.log(FINE, "Using keys in " + bundleStoreConfig.getBundleSecurity().getServerKeyPath());

            return ServerSecurity.getInstance(bundleStoreConfig.getBundleSecurity().getServerKeyPath());
        } catch (Exception e) {
            // TODO Auto-generated catch block
            logger.log(SEVERE, "Catch " + bundleStoreConfig.getBundleSecurity().getServerKeyPath());
            e.printStackTrace();
        }
        return null;
    }
}
