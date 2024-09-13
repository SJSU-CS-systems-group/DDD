package net.discdd.utils;

import net.discdd.bundlesecurity.SecurityUtils;
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.whispersystems.libsignal.ecc.DjbECPublicKey;
import org.whispersystems.libsignal.ecc.ECPrivateKey;
import org.whispersystems.libsignal.ecc.ECPublicKey;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Date;

// thank you for this class copilot!
public class SelfSignedCertificateGenerator {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    public static X509Certificate generateSelfSignedCertificate(ECPublicKey publicKey, ECPrivateKey privateKey) throws Exception {
        // Create subject and issuer
        X500Name issuer = new X500Name("CN=" + SecurityUtils.generateID(publicKey.serialize()));
        X500Name subject = issuer;

        // Set validity period
        Date notBefore = new Date();
        Date notAfter = new Date(notBefore.getTime() + (30 * 24 * 60 * 60 * 1000L)); // 30 day validity

        // Create certificate builder
        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                issuer,
                BigInteger.valueOf(System.currentTimeMillis()),
                notBefore,
                notAfter,
                subject,
                convertToPublicKey(publicKey)
        );

        // Sign the certificate
        ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(convertToPrivateKey(privateKey));
        X509Certificate certificate = new JcaX509CertificateConverter().setProvider("BC").getCertificate(certBuilder.build(contentSigner));

        return certificate;
    }

    public static PrivateKey convertToPrivateKey(ECPrivateKey ecPrivateKey) throws Exception {
        byte[] privateKeyBytes = ecPrivateKey.serialize();
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("EC", "BC");
        return keyFactory.generatePrivate(keySpec);
    }

    public static PublicKey convertToPublicKey(ECPublicKey ecPublicKey) throws Exception {
        byte[] publicKeyBytes = ((DjbECPublicKey)ecPublicKey).getPublicKey();
        X25519PublicKeyParameters publicKeyParams = new X25519PublicKeyParameters(publicKeyBytes, 0);

        SubjectPublicKeyInfo spki = new SubjectPublicKeyInfo(
                new AlgorithmIdentifier(EdECObjectIdentifiers.id_X25519),
                publicKeyParams.getEncoded()
        );

        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(spki.getEncoded());
        KeyFactory keyFactory = KeyFactory.getInstance("X25519", BouncyCastleProvider.PROVIDER_NAME);
        return keyFactory.generatePublic(keySpec);
    }
}