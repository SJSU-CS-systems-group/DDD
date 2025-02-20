package net.discdd.pathutils;

import net.discdd.bundlesecurity.SecurityUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    public final Path toClientPath;

    public final Path toServerPath;
    public final Path tranportKeyPath;
    public final Path serverKeyPath;
    public final Path privateKeyPath;
    public final Path publicKeyPath;
    public final Path certPath;

    public TransportPaths(Path rootDir) {
        this.toClientPath = rootDir.resolve("BundleTransmission/client");
        this.toServerPath = rootDir.resolve("BundleTransmission/server");

        try {
            if (!Files.exists(toClientPath) || !Files.exists(toServerPath)) {
                Files.createDirectories(toClientPath);
                Files.createDirectories(toServerPath);
            }
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to create transport storage directories", e);
        }

        // ----- Transport Security -----
        this.tranportKeyPath = rootDir.resolve(Paths.get(SecurityUtils.BUNDLE_SECURITY_DIR, SecurityUtils.TRANSPORT_KEY_PATH));
        this.tranportKeyPath.toFile().mkdirs();
        this.serverKeyPath = rootDir.resolve(Paths.get(SecurityUtils.BUNDLE_SECURITY_DIR, SecurityUtils.SERVER_KEY_PATH));
        serverKeyPath.toFile().mkdirs();
        this.privateKeyPath = tranportKeyPath.resolve(SecurityUtils.TRANSPORT_IDENTITY_PRIVATE_KEY);
        this.publicKeyPath = tranportKeyPath.resolve(SecurityUtils.TRANSPORT_IDENTITY_KEY);
        this.certPath = tranportKeyPath.resolve(SecurityUtils.TRANSPORT_CERT);
    }
}

