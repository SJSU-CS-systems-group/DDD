package net.discdd.server.commands.bundlesecurity;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

import java.io.File;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.bundlesecurity.ServerSecurity;

import picocli.CommandLine;

@Component
@CommandLine.Command(name = "decrypt-bundle", description = "Decrypt bundle")
public class DecryptBundle implements Callable<Void> {
    private static final Logger logger = Logger.getLogger(DecryptBundle.class.getName());
    @CommandLine.Parameters(arity = "1", index = "0")
    String command;
    @Value("${bundle-server.bundle-transmission.received-processing-directory}")
    private String receivedProcessingDir;
    @CommandLine.Option(names = "--bundle", required = true, description = "Bundle file path")
    private String bundlePath;

    @CommandLine.Option(names = "--decrypted-path", description = "Decrypted bundle file path")
    private String decryptedPath;

    @Autowired
    private ServerSecurity serverSecurity;

    @Override
    public Void call() {
        try {
            logger.log(WARNING, "Decrypting bundle" + bundlePath);

            bundlePath = SecurityUtils.unzip(bundlePath);

            if (decryptedPath == null) {
                decryptedPath = receivedProcessingDir + File.separator + "decrypted" + File.separator;
            }

            serverSecurity.decrypt(Paths.get(bundlePath), Paths.get(decryptedPath));

            int startIndex = bundlePath.lastIndexOf("\\") + 1;

            // Extract the file name using substring
            String fileName = bundlePath.substring(startIndex);

            String decryptedBundlePath = decryptedPath + fileName + ".decrypted";

            decryptedBundlePath = SecurityUtils.unzip(decryptedBundlePath);

            logger.log(INFO, "Finished decrypting " + decryptedBundlePath);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }
}
