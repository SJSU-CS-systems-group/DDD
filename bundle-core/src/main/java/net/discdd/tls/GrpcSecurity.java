package net.discdd.tls;

import lombok.Getter;
import net.discdd.bundlesecurity.SecurityUtils;
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

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

public class GrpcSecurity {
    private static final Logger logger = Logger.getLogger(GrpcSecurity.class.getName());
    private static GrpcSecurity GrpcSecurityInstance = null;

    @Getter
    private X509Certificate grpcCert;
    @Getter
    private KeyPair grpcKeyPair;
    public GrpcSecurity(Path rootPath, String type) throws InvalidAlgorithmParameterException, NoSuchAlgorithmException, NoSuchProviderException, CertificateException, OperatorCreationException, IOException {
        var grpcSecurityPath = rootPath.resolve(SecurityUtils.GRPC_SECURITY_PATH);
        var grpcPublicKeyPath = grpcSecurityPath.resolve(String.format(SecurityUtils.GRPC_PUBLIC_KEY,type));
        var grpcPrivateKeyPath = grpcSecurityPath.resolve(String.format(SecurityUtils.GRPC_PRIVATE_KEY,type));
        var grpcCertPath = grpcSecurityPath.resolve(String.format(SecurityUtils.GRPC_CERT,type));

        try {
            grpcCert = DDDTLSUtil.loadCertFromFile(grpcCertPath);
            grpcKeyPair = DDDTLSUtil.loadKeyPairfromFiles(grpcPublicKeyPath, grpcPrivateKeyPath);

            logger.log(INFO, "Loaded existing certificate and keypair");
        } catch (IOException | CertificateException | NoSuchAlgorithmException | InvalidKeySpecException |
                 InvalidKeyException |
                 NoSuchProviderException e) {
            grpcSecurityPath.toFile().mkdirs();

            grpcKeyPair = DDDTLSUtil.generateKeyPair();
            DDDTLSUtil.writeKeyPairToFile(grpcKeyPair, grpcPublicKeyPath, grpcPrivateKeyPath);
            grpcCert = DDDTLSUtil.getSelfSignedCertificate(grpcKeyPair, DDDTLSUtil.publicKeyToName(grpcKeyPair.getPublic()));
            DDDTLSUtil.writeCertToFile(grpcCert, grpcCertPath);
            logger.log(SEVERE, "No existing certificate and keypair: " + e.getMessage());
            logger.log(INFO, "Generated new certificate and keypair");
        }
    }

    public static synchronized GrpcSecurity initializeInstance(Path rootPath, String type) throws IOException, NoSuchAlgorithmException, org.whispersystems.libsignal.InvalidKeyException, InvalidAlgorithmParameterException, CertificateException, NoSuchProviderException, OperatorCreationException {
        if (GrpcSecurityInstance == null) {
            GrpcSecurityInstance = new GrpcSecurity(rootPath, type);
        } else {
            logger.log(FINE, "[Sec]: Client Security Instance is already initialized!");
        }

        return GrpcSecurityInstance;
    }

    synchronized public static GrpcSecurity getInstance() throws InvalidAlgorithmParameterException, CertificateException, NoSuchAlgorithmException, IOException, NoSuchProviderException, OperatorCreationException {
        return GrpcSecurityInstance;
    }

}