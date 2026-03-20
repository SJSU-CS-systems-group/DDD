package net.discdd.cli;

import net.discdd.tls.DDDTLSUtil;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;

@CommandLine.Command(name = "create-java-key", description = "Create a java key pair")

public class JavaKeyCreator implements Callable<Void> {

    private static final Logger logger = Logger.getLogger(JavaKeyCreator.class.getName());
    @CommandLine.Option(names = "--pub-out", required = true, description = "Public key file path")
    private Path publicKeyPath;
    @CommandLine.Option(names = "--pvt-out", required = true, description = "Private key file path")
    private Path privateKeyPath;

    @Override
    public Void call() {
        // Create a java key pair
        try {
            var keyPair = DDDTLSUtil.generateKeyPair();
            DDDTLSUtil.writeKeyPairToFile(keyPair, publicKeyPath, privateKeyPath);
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to create Java key pair at " + publicKeyPath + " and " + privateKeyPath, e);
        }
        return null;
    }
}
