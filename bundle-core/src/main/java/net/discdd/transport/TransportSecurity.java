package net.discdd.transport;

import net.discdd.bundlesecurity.SecurityUtils;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ecc.ECPrivateKey;
import org.whispersystems.libsignal.ecc.ECPublicKey;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Logger;

public class TransportSecurity {
    private static final Logger logger = Logger.getLogger(TransportSecurity.class.getName());
    public static final String BUNDLE_SECURITY_DIR = "BundleSecurity";
    public static final String SERVER_IDENTITY_PUB = "server_identity.pub";
    public static final String SERVER_KEYS_SUBDIR = "Server_Keys";
    private IdentityKey theirIdentityKey;
    private ECKeyPair transportKeyPair;
    private String transportID;

    public TransportSecurity(Path transportRootPath, InputStream inServerIdentity) throws IOException,
            InvalidKeyException, NoSuchAlgorithmException {
        var tranportKeyPath =
                transportRootPath.resolve(Paths.get(BUNDLE_SECURITY_DIR, SecurityUtils.TRANSPORT_KEY_PATH));

        var serverKeyPath = transportRootPath.resolve(Paths.get(BUNDLE_SECURITY_DIR, SERVER_KEYS_SUBDIR));

        initializeServerKeyPaths(inServerIdentity, transportRootPath);
        InitializeServerKeysFromFiles(serverKeyPath);

        try {
            loadKeysfromFiles(tranportKeyPath);
        } catch (IOException | InvalidKeyException e) {
            logger.severe("Error loading transport keys from files");
            transportKeyPair = Curve.generateKeyPair();
            writeKeysToFiles(tranportKeyPath, transportKeyPair);
        }

        // Create Transport ID
        this.transportID = SecurityUtils.generateID(transportKeyPair.getPublicKey().serialize());
    }

    private Path[] writeKeysToFiles(Path path, ECKeyPair transportKeyPair) throws IOException {
        /* Create Directory if it does not exist */
        path.toFile().mkdirs();
        Path[] identityKeyPaths = { path.resolve(SecurityUtils.TRANSPORT_IDENTITY_KEY),
                path.resolve(SecurityUtils.TRANSPORT_IDENTITY_PRIVATE_KEY) };
        Files.write(path.resolve(SecurityUtils.TRANSPORT_IDENTITY_PRIVATE_KEY),
                    transportKeyPair.getPrivateKey().serialize());

        SecurityUtils.createEncodedPublicKeyFile(transportKeyPair.getPublicKey(), identityKeyPaths[0]);

        return identityKeyPaths;
    }

    private void loadKeysfromFiles(Path tranportKeyPath) throws IOException, InvalidKeyException {
        byte[] transportKeyPvt =
                Files.readAllBytes(tranportKeyPath.resolve(SecurityUtils.TRANSPORT_IDENTITY_PRIVATE_KEY));
        byte[] transportKeyPub = Files.readAllBytes(tranportKeyPath.resolve(SecurityUtils.TRANSPORT_IDENTITY_KEY));

        ECPublicKey basePublicKey = Curve.decodePoint(transportKeyPvt, 0);
        ECPrivateKey basePrivateKey = Curve.decodePrivatePoint(transportKeyPub);

        transportKeyPair = new ECKeyPair(basePublicKey, basePrivateKey);
    }

    private void InitializeServerKeysFromFiles(Path path) throws InvalidKeyException, IOException {
        path.toFile().mkdirs();
        byte[] serverIdentityKey =
                SecurityUtils.decodePublicKeyfromFile(path.resolve(SecurityUtils.SERVER_IDENTITY_KEY));
        theirIdentityKey = new IdentityKey(serverIdentityKey, 0);
    }

    public static void initializeServerKeyPaths(InputStream inServerIdentity, Path rootFolder) throws IOException {
        var bundleSecurityPath = rootFolder.resolve(BUNDLE_SECURITY_DIR);
        var serverKeyPath = bundleSecurityPath.resolve(SERVER_KEYS_SUBDIR);
        serverKeyPath.toFile().mkdirs();

        Path outServerIdentity = serverKeyPath.resolve(SERVER_IDENTITY_PUB);

        Files.copy(inServerIdentity, outServerIdentity, StandardCopyOption.REPLACE_EXISTING);
        inServerIdentity.close();
    }

    public ECPublicKey getServerPublicKey() {
        return theirIdentityKey.getPublicKey();
    }

    public ECPublicKey getTransportPublicKey() {
        return transportKeyPair.getPublicKey();
    }

    public String getTransportID() {
        return this.transportID;
    }
}
