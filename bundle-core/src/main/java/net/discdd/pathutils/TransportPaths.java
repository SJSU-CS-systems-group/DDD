package net.discdd.pathutils;

import lombok.Getter;
import lombok.Setter;

import java.nio.file.Path;

@Getter
public class TransportPaths{
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
    }
}