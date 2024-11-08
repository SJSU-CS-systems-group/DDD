package net.discdd.utils;

import org.whispersystems.curve25519.java.curve_sigs;
import org.whispersystems.curve25519.java.fe_1;
import org.whispersystems.curve25519.java.fe_add;
import org.whispersystems.curve25519.java.fe_frombytes;
import org.whispersystems.curve25519.java.fe_invert;
import org.whispersystems.curve25519.java.fe_mul;
import org.whispersystems.curve25519.java.fe_sub;
import org.whispersystems.curve25519.java.fe_tobytes;
import org.whispersystems.curve25519.java.ge_p3;
import org.whispersystems.curve25519.java.ge_p3_tobytes;
import org.whispersystems.curve25519.java.ge_scalarmult_base;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.DjbECPrivateKey;
import org.whispersystems.libsignal.ecc.DjbECPublicKey;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ecc.ECPrivateKey;
import org.whispersystems.libsignal.ecc.ECPublicKey;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.NamedParameterSpec;

public class KeyUtils {

    public static byte[] reverseEndian(final byte[] byteArray) {
        if (byteArray == null) {
            return null; // Return null if input is null
        }

        byte[] reversedArray = new byte[byteArray.length];
        for (int i = 0; i < byteArray.length; i++) {
            reversedArray[i] = byteArray[byteArray.length - 1 - i];
        }
        return reversedArray;
    }

    public static byte[] convertToEd25519(ECPrivateKey ecPrivateKey) {
        ge_p3 ed_pubkey_point = new ge_p3(); /* Ed25519 pubkey point */
        byte[] ed_pubkey = new byte[32]; /* Ed25519 encoded pubkey */

        /* Convert the Curve25519 privkey to an Ed25519 public key */
        ge_scalarmult_base.ge_scalarmult_base(ed_pubkey_point, ((DjbECPrivateKey) ecPrivateKey).getPrivateKey());
        ge_p3_tobytes.ge_p3_tobytes(ed_pubkey, ed_pubkey_point);

        return ed_pubkey;
    }

    public static byte[] convertToEdType(ECPublicKey ecPublicKey) {
        int[] mont_x = new int[10];
        int[] mont_x_minus_one = new int[10];
        int[] mont_x_plus_one = new int[10];
        int[] inv_mont_x_plus_one = new int[10];
        int[] one = new int[10];
        int[] ed_y = new int[10];
        byte[] ed25519_pubkey = new byte[32];
        byte[] curve25519_pubkey = ((DjbECPublicKey) ecPublicKey).getPublicKey();

        fe_frombytes.fe_frombytes(mont_x, curve25519_pubkey);
        fe_1.fe_1(one);
        fe_sub.fe_sub(mont_x_minus_one, mont_x, one);
        fe_add.fe_add(mont_x_plus_one, mont_x, one);
        fe_invert.fe_invert(inv_mont_x_plus_one, mont_x_plus_one);
        fe_mul.fe_mul(ed_y, mont_x_minus_one, inv_mont_x_plus_one);
        fe_tobytes.fe_tobytes(ed25519_pubkey, ed_y);

        ed25519_pubkey[31] &= 0x7F;
        //ed25519_pubkey[31] |= (curve25519_pubkey[31] & 0x80);
        return ed25519_pubkey;
    }

    public static PublicKey convertToEdECPublicKey(ECPublicKey ecPublicKey) throws InvalidKeySpecException, NoSuchAlgorithmException {
        byte[] publicKeyBytes = convertToEdType(ecPublicKey);
        BigInteger y = new BigInteger(publicKeyBytes);
        EdECPoint edECPoint = new EdECPoint(false, y);
        EdECPublicKeySpec publicKeySpec = new EdECPublicKeySpec(NamedParameterSpec.ED25519, edECPoint);
        KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");

        return keyFactory.generatePublic(publicKeySpec);
    }

    public static PrivateKey convertToEdECPrivateKey(ECPrivateKey ecPrivateKey) throws InvalidKeySpecException, NoSuchAlgorithmException {
//        byte[] privateKeyBytes = convertToEd25519(ecPrivateKey);
        byte[] privateKeyBytes = ((DjbECPrivateKey) ecPrivateKey).getPrivateKey();
        EdECPrivateKeySpec privateKeySpec = new EdECPrivateKeySpec(
                NamedParameterSpec.ED25519, privateKeyBytes);

        KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
        return keyFactory.generatePrivate(privateKeySpec);
    }

    public static KeyPair convertToKeyPair(ECKeyPair ecKeyPair) throws InvalidKeyException, NoSuchAlgorithmException, InvalidKeySpecException {
        KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");

        ECPrivateKey ecPrivateKey = ecKeyPair.getPrivateKey();
        ECPublicKey ecPublicKey = ecKeyPair.getPublicKey();

        byte[] privateKeyBytes = ((DjbECPrivateKey) ecPrivateKey).getPrivateKey();

        byte[] publicKeyBytes = convertToEdType(ecPublicKey);

        BigInteger y = new BigInteger(reverseEndian(publicKeyBytes));
        EdECPoint edECPoint = new EdECPoint(false, y);
        EdECPublicKeySpec publicKeySpec = new EdECPublicKeySpec(NamedParameterSpec.ED25519, edECPoint);

        EdECPrivateKeySpec privateKeySpec = new EdECPrivateKeySpec(
                NamedParameterSpec.ED25519, privateKeyBytes);

        return new KeyPair(keyFactory.generatePublic(publicKeySpec), keyFactory.generatePrivate(privateKeySpec));
    }

//    public static KeyPair convertToKeyPair(ECKeyPair ecKeyPair) throws InvalidKeyException, NoSuchAlgorithmException, InvalidKeySpecException, IOException {
//
//        ECPrivateKey ecPrivateKey = ecKeyPair.getPrivateKey();
//        ECPublicKey ecPublicKey = ecKeyPair.getPublicKey();
//
//        byte[] privateKeyBytes = ((DjbECPrivateKey) ecPrivateKey).getPrivateKey();
//        byte[] publicKeyBytes = convertToEdType(ecPublicKey);  // Ensure this converts to a raw Ed25519 public key
//
//        // Wrap the Ed25519 public and private keys in Bouncy Castle's classes
//        Ed25519PrivateKeyParameters edPrivateKeyParams = new Ed25519PrivateKeyParameters(privateKeyBytes, 0);
//        Ed25519PublicKeyParameters edPublicKeyParams = edPrivateKeyParams.generatePublicKey();
//
//        KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
//
//        // Generate and return the KeyPair
//        PublicKey publicKey = keyFactory.generatePublic(publicKeySpec);
//        PrivateKey privateKey = keyFactory.generatePrivate(privateKeySpec);
//
//        return new KeyPair(publicKey, privateKey);
//    }

    public static ECPublicKey convertToECPublicKey(EdECPublicKey edECPublicKey) throws InvalidKeyException {
        byte[] publicKeyBytes = edECPublicKey.getEncoded();
        byte[] keyBytes = new byte[33];
        keyBytes[0] = Curve.DJB_TYPE;
        byte[] yBytes = edECPublicKey.getPoint().getY().toByteArray();
        System.arraycopy(yBytes, 0, keyBytes, 1, 32);
        System.out.println("\nPublic key bytes:");
        for (byte b : publicKeyBytes) {
            System.out.print(b);
        }
        System.out.println();

        return Curve.decodePoint(keyBytes, 0);
    }

    public static ECPrivateKey convertToECPrivateKey(EdECPrivateKey edECPrivateKey) {
        byte[] privateKeyBytes = edECPrivateKey.getEncoded();
        byte[] keyBytes = new byte[32];
        System.arraycopy(privateKeyBytes, 16, keyBytes, 0, 32);

        return Curve.decodePrivatePoint(keyBytes);
    }

    public static ECPublicKey convertToECPublicKey(ECPrivateKey ecPrivateKey) throws InvalidKeyException {
        byte[] publicKey = new byte[32];
        curve_sigs.curve25519_keygen(publicKey, ((DjbECPrivateKey) ecPrivateKey).getPrivateKey());
        byte[] pub = new byte[33];
        pub[0] = Curve.DJB_TYPE;
        System.arraycopy(publicKey, 0, pub, 1, 32);

        return Curve.decodePoint(pub, 0);
    }

    public static ECKeyPair convertToECKeyPair(EdECPrivateKey edECPrivateKey) throws InvalidKeyException {
        byte[] privateKeyBytes = edECPrivateKey.getEncoded();
        byte[] privateKey = new byte[32];
        System.arraycopy(privateKeyBytes, 16, privateKey, 0, 32);

        byte[] publicKey = new byte[32];
        curve_sigs.curve25519_keygen(publicKey, privateKey);
        byte[] pub = new byte[33];
        pub[0] = Curve.DJB_TYPE;
        System.arraycopy(publicKey, 0, pub, 1, 32);

        return new ECKeyPair(Curve.decodePoint(pub, 0), Curve.decodePrivatePoint(privateKey));
    }



}
