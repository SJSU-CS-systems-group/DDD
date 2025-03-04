package net.discdd.server.config;

import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.tls.GrpcSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.logging.Logger;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

@Configuration
public class GrpcSecurityConfig {
    private static final Logger logger = Logger.getLogger(GrpcSecurityConfig.class.getName());

    @Autowired
    private BundleServerConfig bundleStoreConfig;

    @Bean
    GrpcSecurity createGrpcSecurityInstance() {
        try {
            logger.log(INFO, "Creating GrpcSecurity instance");

            return GrpcSecurity.getInstance(bundleStoreConfig.getGrpcSecurity().getGrpcSecurityPath(), SecurityUtils.SERVER);
        } catch (Exception e) {
            logger.log(SEVERE, "Catch adapter security failures");
            e.printStackTrace();
        }

        return null;
    }
}