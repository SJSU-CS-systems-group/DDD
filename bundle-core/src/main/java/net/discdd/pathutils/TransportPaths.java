package net.discdd.pathutils;

import lombok.Getter;
import lombok.Setter;

import java.nio.file.Path;

@Getter
@Setter
public class TransportPaths{
    public Path ROOT_DIR;
    @Getter
    public Path fromClient;
    @Getter
    public Path fromServer;
    @Getter
    public Path toClient;
    @Getter
    public Path toServer;

    TransportPaths(Path rootDir){
        this.ROOT_DIR = rootDir;
        this.toServer = rootDir.resolve("BundleTransmission/server");
        this.toClient = rootDir.resolve("BundleTransmission/client");
        this.fromClient = rootDir.resolve("BundleTransmission/server");
        this.fromServer = rootDir.resolve("BundleTransmission/client");
    }

}