package net.discdd.transport;

import lombok.Getter;
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
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;

public class TransportSecurity{
    private static final Logger logger = Logger.getLogger(TransportSecurity.class.getName());

    public static final String BUNDLE_SECURITY_DIR = "BundleSecurity";
    public static final String SERVER_IDENTITY_PUB = "server_identity.pub";
    public static final String SERVER_KEYS_SUBDIR = "Server_Keys";
    private IdentityKey theirIdentityKey;
    private KeyPair transportKeyPair;
    @Getter
    private String transportID;
    @Getter
    private X509Certificate certificate;


    public TransportSecurity(Path transportRootPath, InputStream inServerIdentity) throws Exception {
        var tranportKeyPath = transportRootPath.resolve(Paths.get(BUNDLE_SECURITY_DIR,SecurityUtils.TRANSPORT_KEY_PATH));

        var serverKeyPath = transportRootPath.resolve(Paths.get(BUNDLE_SECURITY_DIR, SERVER_KEYS_SUBDIR));

        initializeServerKeyPaths(inServerIdentity, transportRootPath);
        InitializeServerKeysFromFiles(serverKeyPath);

        try {
            loadKeysfromFiles(tranportKeyPath);
        } catch (IOException | InvalidKeyException | InvalidKeySpecException e) {
            logger.severe("Error loading transport keys from files");
            this.transportKeyPair = generateKeyPair();
            writeKeysToFiles(tranportKeyPath, this.transportKeyPair);
        }

        this.certificate = SelfSignedCertificateGenerator.generateSelfSignedCertificate(transportKeyPair);
        this.transportID = SecurityUtils.generateID(transportKeyPair.getPublic().getEncoded());
    }

    private Path[] writeKeysToFiles(Path path, KeyPair transportKeyPair) throws IOException {
        /* Create Directory if it does not exist */
        path.toFile().mkdirs();
        Path[] identityKeyPaths =
                { path.resolve(SecurityUtils.TRANSPORT_IDENTITY_KEY),
                        path.resolve(SecurityUtils.TRANSPORT_IDENTITY_PRIVATE_KEY)};
        Files.write(path.resolve(SecurityUtils.TRANSPORT_IDENTITY_PRIVATE_KEY), transportKeyPair.getPrivate().getEncoded());

        SecurityUtils.createEncodedPublicKeyFile(transportKeyPair.getPublic().getEncoded(), identityKeyPaths[0]);

        return identityKeyPaths;
    }

    private KeyPair generateKeyPair() throws NoSuchAlgorithmException {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC", "BC");
            ECGenParameterSpec ecSpec = new ECGenParameterSpec("secp256r1");
            keyPairGenerator.initialize(ecSpec, new SecureRandom());

            return keyPairGenerator.generateKeyPair();
        } catch (Exception e) {
            logger.log(SEVERE, "Error generating key pair: ", e);
        }

        return null;
    }

//    private void loadKeysfromFiles(Path tranportKeyPath) throws IOException, InvalidKeyException {
//        byte[] transportKeyPvt = SecurityUtils.decodePrivateKeyFromFile(
//                tranportKeyPath.resolve(SecurityUtils.TRANSPORT_IDENTITY_PRIVATE_KEY));
//        byte[] transportKeyPub =
//                SecurityUtils.decodePublicKeyfromFile(tranportKeyPath.resolve(SecurityUtils.TRANSPORT_IDENTITY_KEY));
//
//        return null;
//    }

    private void loadKeysfromFiles(Path tranportKeyPath) throws IOException, InvalidKeyException, InvalidKeySpecException, NoSuchAlgorithmException, NoSuchProviderException {
        byte[] privateKeyBytes = SecurityUtils.decodePrivateKeyFromFile(tranportKeyPath.resolve(SecurityUtils.TRANSPORT_IDENTITY_PRIVATE_KEY));
        byte[] publicKeyBytes = SecurityUtils.decodePublicKeyfromFile(tranportKeyPath.resolve(SecurityUtils.TRANSPORT_IDENTITY_KEY));

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

    public PublicKey getTransportPublicKey() {
        return transportKeyPair.getPublic();
    }

}
