package net.discdd.pathutils;

import lombok.Getter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;

@Getter
public class TransportPaths{
    private static final Logger logger = Logger.getLogger(TransportPaths.class.getName());

    @Getter
    public final Path fromClient;
    @Getter
    public final Path fromServer;
    @Getter
    public final Path toClient;
    @Getter
    public final Path toServer;

    public TransportPaths(Path rootDir){
        this.toServer = rootDir.resolve("BundleTransmission/server");
        this.toClient = rootDir.resolve("BundleTransmission/client");
        this.fromClient = rootDir.resolve("BundleTransmission/server");
        this.fromServer = rootDir.resolve("BundleTransmission/client");

        try {
            if (!Files.exists(rootDir.resolve("BundleTransmission/server")) || !Files.isDirectory(rootDir.resolve("BundleTransmission/client"))) {
                Files.createDirectories(rootDir.resolve("BundleTransmission/client"));
                Files.createDirectories(rootDir.resolve("BundleTransmission/server"));
            }
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to get inventory", e);
        }
            }
}