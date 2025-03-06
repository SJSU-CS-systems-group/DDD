package net.discdd.config;

import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.tls.GrpcSecurity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

@Configuration
public class GrpcSecurityConfig {
    private static final Logger logger = Logger.getLogger(GrpcSecurityConfig.class.getName());
    @Value("${grpc-security.path}")
    private Path grpcSecurityPath;

    @Bean
    GrpcSecurity createGrpcSecurityInstance() {
        try {
            logger.log(INFO, "Creating GrpcSecurity instance");

            return GrpcSecurity.getInstance(grpcSecurityPath, SecurityUtils.SERVER);
        } catch (Exception e) {
            logger.log(SEVERE, "Catch adapter security failures");
            e.printStackTrace();
        }

        return null;
    }
}