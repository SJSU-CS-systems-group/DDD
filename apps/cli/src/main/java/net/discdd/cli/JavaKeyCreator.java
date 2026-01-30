package net.discdd.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import net.discdd.tls.DDDTLSUtil;

import picocli.CommandLine;

@CommandLine.Command(name = "create-java-key", description = "Create a java key pair")

public class JavaKeyCreator implements Callable<Void> {
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
            e.printStackTrace();
        }
        return null;
    }
}
