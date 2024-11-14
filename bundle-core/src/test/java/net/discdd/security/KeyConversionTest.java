package net.discdd.security;

import org.bouncycastle.asn1.edec.EdECObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.ContentVerifier;
import org.bouncycastle.operator.ContentVerifierProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ecc.ECPrivateKey;
import org.whispersystems.libsignal.ecc.ECPublicKey;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.InvalidKeySpecException;

import static net.discdd.utils.KeyUtils.convertToECKeyPair;
import static net.discdd.utils.KeyUtils.convertToKeyPair;
import static net.discdd.utils.KeyUtils.convertToKeyPair2;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class KeyConversionTest {
    private static ECPrivateKey ecPrivateKey;
    private static ECPublicKey ecPublicKey;
    private static ECKeyPair ecKeyPair;
    private static byte[] message;

    @BeforeAll static void setup() {
        ecKeyPair = Curve.generateKeyPair();
        ecPrivateKey = ecKeyPair.getPrivateKey();
        ecPublicKey = ecKeyPair.getPublicKey();

        String payload = "Hello, world!";
        message = payload.getBytes();
    }
    @Test
    void testSignalConvertSignature() throws InvalidKeySpecException, NoSuchAlgorithmException, IOException, OperatorCreationException, InvalidKeyException, SignatureException, org.whispersystems.libsignal.InvalidKeyException {
        KeyPair ourKeyPair = convertToKeyPair2(ecKeyPair);
        var publicKey = ourKeyPair.getPublic();
        var privateKey = ourKeyPair.getPrivate();

        ContentSigner signer = new JcaContentSignerBuilder("Ed25519").build(privateKey);
        signer.getOutputStream().write(message);
        byte[] signedPayload = signer.getSignature();

        ContentVerifierProvider verifierProvider = new JcaContentVerifierProviderBuilder()
                .build(publicKey);
        AlgorithmIdentifier algorithmIdentifier = new AlgorithmIdentifier(EdECObjectIdentifiers.id_Ed25519);
        ContentVerifier verifier = verifierProvider.get(algorithmIdentifier);
        verifier.getOutputStream().write(message);
        boolean verified = verifier.verify(signedPayload);

//        java.security.Signature signature = Signature.getInstance("Ed25519");
//        signature.initSign(privateKey);
//        signature.update(message);
//
//        byte[] signedPayload = signature.sign();
//
//        signature.initVerify(publicKey);
//        signature.update(message);
//
//        boolean verified = signature.verify(signedPayload);

        assertTrue(verified);
    }

    @Test
    void testSignalSignature() throws org.whispersystems.libsignal.InvalidKeyException, NoSuchAlgorithmException, InvalidKeySpecException, InvalidKeyException, SignatureException, IOException {
        byte[] sign = Curve.calculateSignature(ecPrivateKey, message);
        assertTrue(Curve.verifySignature(ecPublicKey, message, sign));

        KeyPair ourKeyPair = convertToKeyPair2(ecKeyPair);
        var publicKey = ourKeyPair.getPublic();
        var privateKey = ourKeyPair.getPrivate();

        java.security.Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(privateKey);
        signature.update(message);

        byte[] signedPayload = signature.sign();

        signature.initVerify(publicKey);
        signature.update(message);
        boolean verified = signature.verify(signedPayload);

        assertTrue(verified);
    }

    @Test
    void testSignalSignature2() throws org.whispersystems.libsignal.InvalidKeyException, NoSuchAlgorithmException, InvalidKeySpecException, InvalidKeyException, SignatureException, IOException {
        var keyPair = convertToKeyPair(ecKeyPair);
        byte[] signature = Curve.calculateSignature(ecPrivateKey, message);

        assertTrue(Curve.verifySignature(ecPublicKey, message, signature));
        java.security.Signature javaSignature = Signature.getInstance("Ed25519");

        javaSignature.initVerify(keyPair.getPublic());
        javaSignature.update(message);
        assertTrue(javaSignature.verify(signature));

        message[0] = '!';
        javaSignature.initSign(keyPair.getPrivate());
        javaSignature.update(message);
        byte[] sign = javaSignature.sign();

        assertTrue(Curve.verifySignature(ecPublicKey, message, sign));
    }

    @Test
    void testEdECKeyConvertSignature() throws org.whispersystems.libsignal.InvalidKeyException, NoSuchAlgorithmException {
        var keyGenerator = KeyPairGenerator.getInstance("Ed25519");
        keyGenerator.initialize(255);
        var keyPair = keyGenerator.generateKeyPair();
//        ECPrivateKey privateKey = convertToECPrivateKey((EdECPrivateKey) keyPair.getPrivate());
//        ECPublicKey publicKey = convertToECPublicKey(privateKey);

        ECKeyPair ecKeyPair = convertToECKeyPair((EdECPrivateKey) keyPair.getPrivate());
        ECPrivateKey privateKey = ecKeyPair.getPrivateKey();
        ECPublicKey publicKey = ecKeyPair.getPublicKey();

        byte[] signature = Curve.calculateSignature(privateKey, message);
        assertTrue(Curve.verifySignature(publicKey, message, signature));
    }

    @Test
    void testEdECKeySignature() throws org.whispersystems.libsignal.InvalidKeyException, NoSuchAlgorithmException, InvalidKeyException, SignatureException, IOException, OperatorCreationException {
        var keyGenerator = KeyPairGenerator.getInstance("Ed25519");
        keyGenerator.initialize(255);
        var keyPair = keyGenerator.generateKeyPair();
        EdECPublicKey publicKey = (EdECPublicKey) keyPair.getPublic();
        EdECPrivateKey privateKey = (EdECPrivateKey) keyPair.getPrivate();

        java.security.Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(privateKey);
        signature.update(message);

        byte[] signedPayload = signature.sign();

        signature.initVerify(publicKey);
        signature.update(message);

        boolean verified = signature.verify(signedPayload);
//        ContentSigner signer = new JcaContentSignerBuilder("Ed25519").build(privateKey);
//        signer.getOutputStream().write(message);
//        byte[] signedPayload = signer.getSignature();
//
//        ContentVerifierProvider verifierProvider = new JcaContentVerifierProviderBuilder()
//                .build(publicKey);
//        AlgorithmIdentifier algorithmIdentifier = new AlgorithmIdentifier(EdECObjectIdentifiers.id_Ed25519);
//        ContentVerifier verifier = verifierProvider.get(algorithmIdentifier);
//        verifier.getOutputStream().write(message);
//
//        boolean verified = verifier.verify(signedPayload);

        assertTrue(verified);
    }
}
