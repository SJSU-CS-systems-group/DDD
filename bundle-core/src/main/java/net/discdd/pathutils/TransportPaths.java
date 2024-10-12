package net.discdd.pathutils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;

public class TransportPaths{
    private static final Logger logger = Logger.getLogger(TransportPaths.class.getName());

    private final Path clientPath;
    private final Path serverPath;

    public TransportPaths(Path rootDir){
        this.clientPath = rootDir.resolve("BundleTransmission/client");
        this.serverPath = rootDir.resolve("BundleTransmission/server");

        try {
            if (!Files.exists(clientPath) || !Files.isDirectory(serverPath)) {
                Files.createDirectories(clientPath);
                Files.createDirectories(serverPath);
            }
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to get inventory", e);
        }
    }

    public Path getToClient(){ return clientPath; }

    public Path getToServer(){ return serverPath; }

    public Path getFromClient(){ return serverPath; }

    public Path getFromServer(){ return clientPath; }
}