package net.discdd.pathutils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;

public class TransportPaths{
    private static final Logger logger = Logger.getLogger(TransportPaths.class.getName());

    public final Path toClientPath;

    public final Path toServerPath;

    public TransportPaths(Path rootDir){
        this.toClientPath = rootDir.resolve("BundleTransmission/client");
        this.toServerPath = rootDir.resolve("BundleTransmission/server");

        try {
            if (!Files.exists(toClientPath) || !Files.isDirectory(toServerPath)) {
                Files.createDirectories(toClientPath);
                Files.createDirectories(toServerPath);
            }
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to get inventory", e);
        }
    }
}