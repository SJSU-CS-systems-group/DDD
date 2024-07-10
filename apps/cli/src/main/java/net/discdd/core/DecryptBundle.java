package net.discdd.core;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

import com.ddd.bundlesecurity.SecurityUtils;
import com.ddd.bundlesecurity.ServerSecurity;
import picocli.CommandLine;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

@CommandLine.Command(name = "decrypt-bundle", description = "Decrypt bundle")
public class DecryptBundle implements Callable<Void> {
    private static final Logger logger = Logger.getLogger(DecryptBundle.class.getName());

    @CommandLine.Option(names = "--applicationYaml", required = true, description = "an application yaml")
    private static File applicationYml;
    @CommandLine.Option(names = "--bundle", required = true, description = "Bundle file path")
    private String bundlePath;
    @CommandLine.Option(names = "--decrypted-path", description = "Decrypted bundle file path")
    private String decryptedPath;
    @CommandLine.Option(names = "--appProps", required = true, description = "Personal application properties file path")
    private static String appProps;

    @Override
    public Void call() {
        try {
            ServerSecurity serverSecurity = new ServerSecurity(CliUtils.getServerSecurity(applicationYml, appProps));
            String receivedProcessingDir = CliUtils.getReceivedProcessingDirectory(applicationYml, appProps);

            logger.log(WARNING, "Decrypting bundle" + bundlePath);

            bundlePath = SecurityUtils.unzip(bundlePath);

            if (decryptedPath == null) {
                decryptedPath = receivedProcessingDir + File.separator + "decrypted" + File.separator;
            }

            Path fileName = serverSecurity.decrypt(Paths.get(bundlePath), Paths.get(decryptedPath));

            String decryptedBundlePath = fileName.toString();

            decryptedBundlePath = SecurityUtils.unzip(decryptedBundlePath);

            logger.log(INFO, "Finished decrypting " + decryptedBundlePath);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}