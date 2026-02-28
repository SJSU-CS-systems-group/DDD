package net.discdd.cli;

import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.bundlesecurity.ServerSecurity;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

@CommandLine.Command(name = "decrypt-bundle", description = "Decrypt bundle")
public class DecryptBundle implements Callable<Void> {
    static {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%5$s%6$s%n");
    }

    private static final Logger logger = Logger.getLogger(DecryptBundle.class.getName());

    @CommandLine.Option(names = "--applicationYaml", required = true, description = "an application yaml")
    private static File applicationYml;
    @CommandLine.Option(names = "--bundle", required = true, description = "Bundle file path")
    private String bundlePath;
    @CommandLine.Option(names = "--decrypted-path", description = "Decrypted bundle file path")
    private String decryptedPath;
    @CommandLine.Option(names = "--appProps", required = true, description = "Personal application properties file " +
            "path")
    private static String appProps;

    @Override
    public Void call() {
        try {
            ServerSecurity serverSecurity = new ServerSecurity(CliUtils.getServerSecurity(applicationYml, appProps));
            String receivedProcessingDir = CliUtils.getReceivedProcessingDirectory(applicationYml, appProps);

            logger.log(INFO, "Decrypting bundle " + bundlePath);

            bundlePath = SecurityUtils.unzip(bundlePath);

            if (decryptedPath == null) {
                decryptedPath = receivedProcessingDir + File.separator + "decrypted" + File.separator;
            }

            Path fileName = serverSecurity.decrypt(Paths.get(bundlePath), Paths.get(decryptedPath));

            String decryptedBundlePath = fileName.toString();

            decryptedBundlePath = SecurityUtils.unzip(decryptedBundlePath);

            logger.log(INFO, "Finished decrypting " + decryptedBundlePath);
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to decrypt bundle file", e);
        }
        return null;
    }
}