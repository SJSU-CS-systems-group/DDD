package net.discdd.app.echo;

import net.discdd.security.AdapterSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

@Configuration
public class AdapterSecurityConfig {
    private static final Logger logger = Logger.getLogger(AdapterSecurityConfig.class.getName());

    @Autowired
    ApplicationContext context;

    @Value("${echo-server.root-dir}")
    private Path adapterPath;

    @Bean
    AdapterSecurity createAdapterSecurityInstance() {
        try {
            logger.log(INFO, "Creating adapter security instance");

            return AdapterSecurity.getInstance(adapterPath);
        } catch (Exception e) {
            // TODO Auto-generated catch block
            logger.log(SEVERE, "Catch adapter security failures");
            e.printStackTrace();
        }
        return null;
    }
}
