package net.discdd.pathutils;

import net.discdd.bundlesecurity.SecurityUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;

// BundleTransport
//    |_ files
//        |_ BundelSecurity (TO-DO)
//            |_ Server_Keys - server public keys
//            |_ Transport_Keys - transport key pairs
//        |_ BundleTransmission
//            |_ client - bundles to send to client + recencyBlob
//            |_ server - bundles to send to server

public class TransportPaths {
    private static final Logger logger = Logger.getLogger(TransportPaths.class.getName());
    public final Path toRootDir;
    public final Path toClientPath;
    public final Path toServerPath;
    public final Path grpcSecurityPath;
    public final Path crashReportPath;
    public TransportPaths(Path rootDir) {

        this.toRootDir = rootDir;
        this.toClientPath = rootDir.resolve("BundleTransmission/client");
        this.toServerPath = rootDir.resolve("BundleTransmission/server");
        this.crashReportPath = rootDir.resolve("crash_report.txt");

        try {
            if (!Files.exists(toClientPath) || !Files.exists(toServerPath)) {
                Files.createDirectories(toClientPath);
                Files.createDirectories(toServerPath);
            }
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to create transport storage directories", e);
        }

        this.grpcSecurityPath = rootDir.resolve(SecurityUtils.GRPC_SECURITY_PATH);
    }
}

