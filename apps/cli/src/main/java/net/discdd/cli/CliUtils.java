package net.discdd.cli;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;

public class CliUtils {
    private static final Logger logger = Logger.getLogger(CliUtils.class.getName());

    public static String getReceivedProcessingDirectory(File applicationYml, String appProps) {
        Properties prop = new Properties();
        String resultString = "";
        if (!applicationYml.exists()) {
            System.out.println("Unable to getReceivedProcessingDirectory- lack of application yaml file");
            return null;
        }
        try (InputStream input = new FileInputStream(applicationYml)) {
            prop.load(input);
            String s = prop.getProperty("received-processing-directory");
            s = s.substring(1, s.length() - 1);
            resultString = s.replace("${bundle-server.bundle-store-root}", loadRoot(appProps));
            return resultString;
        } catch (IOException e) {
            System.out.println("No received-processing-directory found in application yaml." + e);
            return resultString;
        }
    }

    public static Path getServerSecurity(File applicationYml, String appProps) {
        Properties prop = new Properties();
        String resultString = "";
        if (!applicationYml.exists()) {
            System.out.println("Unable to getServerSecurity- lack of application yaml file");
            return null;
        }
        try (InputStream input = new FileInputStream(applicationYml)) {
            prop.load(input);
            String s = prop.getProperty("server-key-path");
            s = s.substring(1, s.length() - 1);
            resultString = s.replace("${bundle-server.bundle-store-root}", loadRoot(appProps));
            return Path.of(resultString);
        } catch (IOException e) {
            System.out.println("No received-processing-directory found in application yaml." + e);
            return null;
        }
    }

    public static String loadRoot(String appProps) {
        Properties properties = new Properties();
        String s = "";
        try (FileInputStream input = new FileInputStream(appProps)) {
            properties.load(input);
            s = properties.getProperty("bundle-server.bundle-store-root");
        } catch (IOException e) {
            logger.log(SEVERE, "Failed to load bundle store root from config file", e);
        }
        return s;
    }
}
