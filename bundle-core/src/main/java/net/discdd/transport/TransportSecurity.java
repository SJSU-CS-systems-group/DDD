package net.discdd.transport;

import lombok.Getter;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.pathutils.TransportPaths;
import net.discdd.tls.DDDTLSUtil;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.InvalidKeyException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.util.logging.Logger;


public class TransportSecurity {
    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private static final Logger logger = Logger.getLogger(TransportSecurity.class.getName());
    private IdentityKey theirIdentityKey;
    @Getter
    private KeyPair transportKeyPair;
    @Getter
    private String transportID;
    @Getter
    private X509Certificate transportCert;
    private static TransportPaths transportPaths;

    public TransportSecurity(TransportPaths transportPaths) throws IOException, NoSuchAlgorithmException, CertificateException, OperatorCreationException, NoSuchProviderException, InvalidAlgorithmParameterException {
        this.transportPaths = transportPaths;

        try {
            transportKeyPair = DDDTLSUtil.loadKeyPairfromFiles(transportPaths.publicKeyPath, transportPaths.privateKeyPath);
            transportCert = DDDTLSUtil.loadCertFromFile(transportPaths.certPath);
        } catch (IOException | java.security.InvalidKeyException | InvalidKeySpecException e) {
            logger.severe("Error loading transport keys from files");

            transportKeyPair = DDDTLSUtil.generateKeyPair();
            DDDTLSUtil.writeKeyPairToFile(transportKeyPair, transportPaths.publicKeyPath, transportPaths.privateKeyPath);

            transportCert = DDDTLSUtil.getSelfSignedCertificate(transportKeyPair, DDDTLSUtil.publicKeyToName(transportKeyPair.getPublic()));
            DDDTLSUtil.writeCertToFile(transportCert, transportPaths.certPath);
        }

        // Create Transport ID
        this.transportID = DDDTLSUtil.publicKeyToName(transportKeyPair.getPublic());
    }

    private void InitializeServerKeysFromFiles(Path path) throws InvalidKeyException, IOException {
        path.toFile().mkdirs();
        byte[] serverIdentityKey =
                SecurityUtils.decodePublicKeyfromFile(path.resolve(SecurityUtils.SERVER_IDENTITY_KEY));
        theirIdentityKey = new IdentityKey(serverIdentityKey, 0);
    }

    public static void initializeServerKeyPaths(InputStream inServerIdentity) throws IOException {
        Path outServerIdentity = transportPaths.serverKeyPath.resolve(SecurityUtils.SERVER_IDENTITY_KEY);

        Files.copy(inServerIdentity, outServerIdentity, StandardCopyOption.REPLACE_EXISTING);
        inServerIdentity.close();
    }
}
