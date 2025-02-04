package net.discdd.security;

import lombok.Getter;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.tls.DDDTLSUtil;

import java.io.IOException;
import java.nio.file.Path;
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
    public AdapterSecurity(Path adapterPath) {
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
            logger.log(SEVERE, "Could not load adapter certificate: " + e.getMessage());
            System.exit(1);
        }
    }

    synchronized public static AdapterSecurity getInstance(Path adapterPath) {
        return new AdapterSecurity(adapterPath);
    }

}
