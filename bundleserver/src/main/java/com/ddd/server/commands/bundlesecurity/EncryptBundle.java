package com.ddd.server.commands.bundlesecurity;

import com.ddd.server.bundlesecurity.ServerSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;

@Component
@CommandLine.Command(name = "encrypt-bundle", description = "Encrypt bundle")
public class EncryptBundle implements Callable<Void> {
    private static final Logger logger = Logger.getLogger(EncryptBundle.class.getName());
    @Value("${bundle-server.bundle-transmission.received-processing-directory}")
    private String receivedProcessingDir;

    @CommandLine.Parameters(arity = "1", index = "0")
    String command;

    @CommandLine.Option(names = "--bundle", required = true, description = "Bundle file path")
    private String bundlePath;

    @CommandLine.Option(names = "--encrypted-path", description = "Encrypted bundle file path")
    private String encPath;

    @CommandLine.Option(names = "--clientId", required = true, description = "Client ID")
    private String clientId;

    @Autowired
    private ServerSecurity serverSecurity;

    @Override
    public Void call() {
        logger.log(INFO, "Encrypting bundle " + bundlePath);

        if (encPath == null) {
            encPath = receivedProcessingDir + File.separator + "encrypted" + File.separator;
        }

        Path path = Paths.get(bundlePath);
        String bundleId = path.getFileName().toString().replace(".decrypted", "");

        try {
            // Encrypt the bundle
            String[] paths = serverSecurity.encrypt(bundlePath, encPath, bundleId, clientId);

            Arrays.stream(paths).forEach(System.out::println);

            logger.log(INFO, "Finished encrypting " + bundlePath);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }
}
