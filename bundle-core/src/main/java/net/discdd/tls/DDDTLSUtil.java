package net.discdd.tls;

import net.discdd.bundlesecurity.DDDPEMEncoder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

import javax.net.ssl.KeyManagerFactory;
import javax.security.auth.x500.X500Principal;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

public class DDDTLSUtil {
    public static String publicKeyToName(PublicKey key) {
        var edKey = (ECPublicKey) key;
        return new String(Base64.getUrlEncoder().encode(edKey.getEncoded())).replace("=", "");
    }

    public static KeyPair generateKeyPair() throws NoSuchAlgorithmException, NoSuchProviderException,
            InvalidAlgorithmParameterException {
        var keyGenerator = KeyPairGenerator.getInstance("EC");
        keyGenerator.initialize(new ECGenParameterSpec("secp256r1"));
        return keyGenerator.generateKeyPair();
    }

    public static void writeKeyPairToFile(KeyPair keyPair, Path publicKeyPath, Path privateKeyPath) throws IOException {
        Files.write(privateKeyPath,
                    DDDPEMEncoder.encode(keyPair.getPrivate().getEncoded(), DDDPEMEncoder.privateKeyType).getBytes(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

        Files.write(publicKeyPath,
                    DDDPEMEncoder.encode(keyPair.getPublic().getEncoded(), DDDPEMEncoder.publicKeyType).getBytes(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
    }

    public static KeyPair loadKeyPairfromFiles(Path publicKeyPath, Path privateKeyPath) throws IOException,
            NoSuchAlgorithmException, InvalidKeySpecException, java.security.InvalidKeyException,
            NoSuchProviderException {
        byte[] keyPub = DDDPEMEncoder.decodeFromFile(publicKeyPath, DDDPEMEncoder.publicKeyType);
        byte[] keyPvt = DDDPEMEncoder.decodeFromFile(privateKeyPath, DDDPEMEncoder.privateKeyType);

        X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(keyPub);
        PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(keyPvt);

        KeyFactory keyFactory = KeyFactory.getInstance("EC");

        return new KeyPair(keyFactory.generatePublic(publicKeySpec), keyFactory.generatePrivate(privateKeySpec));
    }

    public static X509Certificate getSelfSignedCertificate(KeyPair pair, String commonName) throws
            OperatorCreationException, CertificateException {
        var csrSigner = new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate());
        var csr =
                new JcaPKCS10CertificationRequestBuilder(new X500Principal("CN=" + commonName), pair.getPublic()).build(
                        csrSigner);
        var start = Date.from(Instant.now().minus(365, ChronoUnit.DAYS));
        var end = Date.from(Instant.now().plus(365, ChronoUnit.DAYS));
        var cert = new X509v3CertificateBuilder(csr.getSubject(),
                                                BigInteger.ONE,
                                                start,
                                                end,
                                                csr.getSubject(),
                                                csr.getSubjectPublicKeyInfo()).build(csrSigner);
        return new JcaX509CertificateConverter().getCertificate(cert);
    }

    public static KeyManagerFactory getKeyManagerFactory(KeyPair keyPair, X509Certificate cert) throws Exception {
        var keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("key", keyPair.getPrivate(), null, new X509Certificate[] { cert });

        var keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, null);

        return keyManagerFactory;
    }

    public static void writeCertToFile(X509Certificate cert, Path path) throws IOException,
            CertificateEncodingException {
        byte[] encodedCert = DDDPEMEncoder.encode(cert.getEncoded(), DDDPEMEncoder.CERTIFICATE).getBytes();

        Files.write(path, encodedCert, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        System.out.println("Certificate written to " + path);
    }

    public static X509Certificate loadCertFromFile(Path path) throws IOException, CertificateException {
        byte[] decodedCert = DDDPEMEncoder.decodeFromFile(path, DDDPEMEncoder.CERTIFICATE);

        org.bouncycastle.jcajce.provider.asymmetric.x509.CertificateFactory cf =
                new org.bouncycastle.jcajce.provider.asymmetric.x509.CertificateFactory();

        return (X509Certificate) cf.engineGenerateCertificate(new ByteArrayInputStream(decodedCert));
    }
}
