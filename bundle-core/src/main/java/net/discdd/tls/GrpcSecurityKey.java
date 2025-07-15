package net.discdd.tls;

import net.discdd.bundlesecurity.SecurityUtils;
import org.bouncycastle.jcajce.provider.asymmetric.X509;
import org.bouncycastle.operator.OperatorCreationException;

import java.io.IOException;
import java.nio.file.Path;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

public class GrpcSecurityKey {
    private static final Logger logger = Logger.getLogger(GrpcSecurityKey.class.getName());

    final public X509Certificate grpcCert;
    final public KeyPair grpcKeyPair;

    public GrpcSecurityKey(Path grpcSecurityPath, String type) throws InvalidAlgorithmParameterException,
            NoSuchAlgorithmException, NoSuchProviderException, CertificateException, OperatorCreationException,
            IOException {
        var grpcPublicKeyPath = grpcSecurityPath.resolve(String.format(SecurityUtils.GRPC_PUBLIC_KEY, type));
        var grpcPrivateKeyPath = grpcSecurityPath.resolve(String.format(SecurityUtils.GRPC_PRIVATE_KEY, type));
        var grpcCertPath = grpcSecurityPath.resolve(String.format(SecurityUtils.GRPC_CERT, type));

        KeyPair keyPair;
        X509Certificate cert;
        try {
            keyPair = DDDTLSUtil.loadKeyPairfromFiles(grpcPublicKeyPath, grpcPrivateKeyPath);
            logger.log(INFO, "Loaded key pair from file: " + grpcPublicKeyPath);
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException | InvalidKeyException |
                 NoSuchProviderException e) {
            grpcSecurityPath.toFile().mkdirs();

            logger.log(WARNING, "No existing keypair: " + e.getMessage());
            keyPair = DDDTLSUtil.generateKeyPair();
            DDDTLSUtil.writeKeyPairToFile(keyPair, grpcPublicKeyPath, grpcPrivateKeyPath);
            logger.log(INFO, "Generated new keypair");
        }
        try {
            cert = DDDTLSUtil.loadCertFromFile(grpcCertPath);
            logger.log(INFO, "Loaded certificate from file: " + grpcCertPath);
        } catch (IOException | CertificateException e) {
            cert = DDDTLSUtil.getSelfSignedCertificate(keyPair, DDDTLSUtil.publicKeyToName(keyPair.getPublic()));
            DDDTLSUtil.writeCertToFile(cert, grpcCertPath);
            logger.log(INFO, "Generated new certificate");
        }
        grpcCert = cert;
        grpcKeyPair = keyPair;
        logger.log(INFO, "Loaded keypair");

    }
}