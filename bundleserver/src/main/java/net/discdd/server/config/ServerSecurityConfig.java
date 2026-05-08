package net.discdd.server.config;

import net.discdd.bundlesecurity.ServerSecurity;
import net.discdd.server.commands.CommandProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.SEVERE;

@Configuration
public class ServerSecurityConfig {
    private static final Logger logger = Logger.getLogger(ServerSecurityConfig.class.getName());

    @Autowired
    private BundleServerConfig bundleStoreConfig;

    @Bean
    ServerSecurity createServerSecurityInstance() {
        // Skip bean creation during CLI command execution (e.g., generate-keys)
        if (isCliCommand()) {
            return null;
        }

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

    private boolean isCliCommand() {
        String[] args = getCommandLineArgs();
        return CommandProcessor.checkForCommand(args);
    }

    private String[] getCommandLineArgs() {
        // Get args from system property set by BundleServerApplication
        String argsStr = System.getProperty("bundleserver.cli.args", "");
        if (argsStr.isEmpty()) {
            return new String[0];
        }
        return argsStr.split("\\|");
    }
}
