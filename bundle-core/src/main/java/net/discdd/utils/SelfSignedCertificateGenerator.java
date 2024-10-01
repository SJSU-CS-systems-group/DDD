package net.discdd.utils;

import net.discdd.bundlesecurity.SecurityUtils;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.crypto.params.ECKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
//import org.bouncycastle.jce.spec.ECParameterSpec;
//import org.bouncycastle.jce.spec.ECPrivateKeySpec;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.DjbECPrivateKey;
import org.whispersystems.libsignal.ecc.DjbECPublicKey;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ecc.ECPrivateKey;
import org.whispersystems.libsignal.ecc.ECPublicKey;

import java.io.*;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.security.spec.XECPrivateKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;


import static org.bouncycastle.jce.provider.BouncyCastleProvider.getPrivateKey;

// thank you for this class copilot!
// -Djavax.net.debug=ssl:handshake
public class SelfSignedCertificateGenerator {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    public static X509Certificate generateSelfSignedCertificate(KeyPair keyPair) throws Exception {
        // Create subject and issuer
        X500Name issuer = new X500Name("CN=" + SecurityUtils.generateID(keyPair.getPublic().getEncoded()));
        System.out.println("ServerId: " + SecurityUtils.generateID(keyPair.getPublic().getEncoded()));
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
                keyPair.getPublic()
        );

        // Sign the certificate
        ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(keyPair.getPrivate());
        X509Certificate certificate = new JcaX509CertificateConverter().setProvider("BC").getCertificate(certBuilder.build(contentSigner));
        return certificate;
    }

    public static X509Certificate generateSelfSignedCertificate(ECPublicKey publicKey, ECPrivateKey privateKey) throws Exception {
        // Create subject and issuer
            X500Name issuer = new X500Name("CN=" + SecurityUtils.generateID(publicKey.serialize()));
            System.out.println("ServerId: " + SecurityUtils.generateID(publicKey.serialize()));
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
        BigInteger s = new BigInteger(1, privateKeyBytes);

        KeyFactory keyFactory = KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));

        ECParameterSpec ecParameterSpec = parameters.getParameterSpec(ECParameterSpec.class);
        ECPrivateKeySpec privateKeySpec = new ECPrivateKeySpec(s, ecParameterSpec);

        return keyFactory.generatePrivate(privateKeySpec);
    }

    public static PublicKey convertToPublicKey(ECPublicKey ecPublicKey) throws Exception {
        // Get the public key bytes
        byte[] publicKeyBytes = ((DjbECPublicKey) ecPublicKey).getPublicKey();

        // Initialize BouncyCastle Provider
        Security.addProvider(new BouncyCastleProvider());

        // Generate EC Parameters for secp256r1
        KeyFactory keyFactory = KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec ecParameterSpec = params.getParameterSpec(ECParameterSpec.class);

        // Convert public key bytes into a point on the curve
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(publicKeyBytes, 1, 16));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(publicKeyBytes, 17, 33));
        ECPoint point = new ECPoint(x, y);

        // Create the key spec for the public key
        ECPublicKeySpec keySpec = new ECPublicKeySpec(point, ecParameterSpec);
        return keyFactory.generatePublic(keySpec);
    }

    public static void writeCertificateToFile(X509Certificate certificate, Path outputPath) throws IOException {
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(new FileWriter(outputPath.toFile()))) {
            pemWriter.writeObject(certificate);
        }
    }

    public static ECKeyPair convertKeyPair(Path privateKeyPath, Path publicKeyPath) throws Exception {
        byte[] privateKeyBytes = SecurityUtils.decodePrivateKeyFromFile(privateKeyPath);
        byte[] publicKeyBytes = SecurityUtils.decodePublicKeyfromFile(publicKeyPath);

        return new ECKeyPair(Curve.decodePoint(publicKeyBytes, 0), Curve.decodePrivatePoint(privateKeyBytes));
    }
}


