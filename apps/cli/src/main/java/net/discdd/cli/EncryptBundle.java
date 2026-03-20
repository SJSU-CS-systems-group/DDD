package net.discdd.cli;

import net.discdd.bundlesecurity.ServerSecurity;
import net.discdd.utils.BundleUtils;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

@CommandLine.Command(name = "encrypt-bundle", description = "Encrypt bundle")
public class EncryptBundle implements Callable<Void> {
    static {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%5$s%6$s%n");
    }

    private static final Logger logger = Logger.getLogger(EncryptBundle.class.getName());

    @CommandLine.Option(names = "--decrypted-bundle", required = true, description = "Bundle file path")
    private String bundlePath;

    @CommandLine.Option(names = "--encrypted-path", description = "Encrypted bundle file path")
    private String encPath;

    @CommandLine.Option(names = "--clientId", required = true, description = "Client ID")
    private String clientId;

    @CommandLine.Option(names = "--applicationYaml", required = true, description = "an application yaml")
    private static File applicationYml;

    @CommandLine.Option(names = "--appProps", required = true, description = "Personal application properties file " +
            "path")
    private static String appProps;

    @Override
    public Void call() {
        ServerSecurity serverSecurity = new ServerSecurity(CliUtils.getServerSecurity(applicationYml, appProps));
        String receivedProcessingDir = CliUtils.getReceivedProcessingDirectory(applicationYml, appProps);

        logger.log(INFO, "Encrypting bundle " + bundlePath);

        if (encPath == null) {
            encPath = receivedProcessingDir + File.separator + "encrypted" + File.separator;
        }
        Path path = Paths.get(bundlePath);
        String bundleId = path.getFileName().toString().replace(".decrypted", "");

        try (var encryptedBundleOs = Files.newOutputStream(path,
                                                           StandardOpenOption.CREATE,
                                                           StandardOpenOption.TRUNCATE_EXISTING)) {
            var identityPublicKey = serverSecurity.getClientIdentityPublicKey(clientId);
            var baseKey = serverSecurity.getClientBaseKey(clientId);
            BundleUtils.encryptPayloadAndCreateBundle((inputStream, outputStream) -> serverSecurity.encrypt(clientId,
                                                                                                            inputStream,
                                                                                                            outputStream),
                                                      identityPublicKey,
                                                      baseKey,
                                                      serverSecurity.getIdentityPublicKey().getPublicKey(),
                                                      bundleId,
                                                      Files.newInputStream(path),
                                                      encryptedBundleOs);
            logger.log(INFO, "Finished encrypting " + bundlePath);
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to encrypt bundle " + bundlePath, e);
        }
        return null;
    }
}
