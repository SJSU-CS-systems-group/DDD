package net.discdd.security;

import lombok.Getter;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.tls.DDDTLSUtil;
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

import static java.util.logging.Level.SEVERE;

public class AdapterSecurity {
    private static final Logger logger = Logger.getLogger(AdapterSecurity.class.getName());

    @Getter
    private X509Certificate adapterCert;
    @Getter
    private KeyPair adapterKeyPair;
    public AdapterSecurity(Path adapterPath) throws InvalidAlgorithmParameterException, NoSuchAlgorithmException, NoSuchProviderException, CertificateException, OperatorCreationException, IOException {
        var adapterSecurity = adapterPath.resolve(SecurityUtils.ADAPTER_SECURITY_PATH);
        var adapterPublicKeyPath = adapterSecurity.resolve(SecurityUtils.ADAPTER_JAVA_PUBLIC_KEY);
        var adapterPrivateKeyPath = adapterSecurity.resolve(SecurityUtils.ADAPTER_JAVA_PRIVATE_KEY);
        var adapterCertPath = adapterSecurity.resolve(SecurityUtils.ADAPTER_CERT);

        try {
            adapterCert = DDDTLSUtil.loadCertFromFile(adapterCertPath);
            adapterKeyPair = DDDTLSUtil.loadKeyPairfromFiles(adapterPublicKeyPath, adapterPrivateKeyPath);
        } catch (IOException | CertificateException | NoSuchAlgorithmException | InvalidKeySpecException |
                 InvalidKeyException |
                 NoSuchProviderException e) {
            adapterSecurity.toFile().mkdirs();

            adapterKeyPair = DDDTLSUtil.generateKeyPair();
            DDDTLSUtil.writeKeyPairToFile(adapterKeyPair, adapterPublicKeyPath, adapterPrivateKeyPath);
            adapterCert = DDDTLSUtil.getSelfSignedCertificate(adapterKeyPair, DDDTLSUtil.publicKeyToName(adapterKeyPair.getPublic()));
            DDDTLSUtil.writeCertToFile(adapterCert, adapterCertPath);
            logger.log(SEVERE, "Could not load adapter certificate ", e);
        }
    }

    synchronized public static AdapterSecurity getInstance(Path adapterPath) throws InvalidAlgorithmParameterException, CertificateException, NoSuchAlgorithmException, IOException, NoSuchProviderException, OperatorCreationException {
        return new AdapterSecurity(adapterPath);
    }

}