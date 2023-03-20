package com.ddd.client.bundlesecurity;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import java.util.Base64;
import java.util.List;

import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.impl.InMemorySignalProtocolStore;
import org.whispersystems.libsignal.util.KeyHelper;

public class SecurityUtils {
    public static final String PAYLOAD_FILENAME = "payload";
    public static final String SIGN_FILENAME = PAYLOAD_FILENAME + ".signature";
    public static final String BUNDLEID_FILENAME = "bundle.id";
    public static final String DECRYPTED_FILE_EXT = ".decrypted";

    static class ClientSession {
        IdentityKey             IdentityKey;
        ECPublicKey             BaseKey;

        SessionCipher           cipherSession;
        SessionRecord           serverSessionRecord;
    };

    public static String generateID(String publicKeyPath) throws NoSessionException, IOException, InvalidKeyException, NoSuchAlgorithmException
    {
        byte[] publicKey = decodePublicKeyfromFile(publicKeyPath);
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] hashedKey = md.digest(publicKey);
        return Base64.getEncoder().encodeToString(hashedKey);
    }

    public static String generateID(byte[] publicKey) throws NoSuchAlgorithmException
    {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] hashedKey = md.digest(publicKey);
        return Base64.getEncoder().encodeToString(hashedKey);
    }

    public static void createEncodedPublicKeyFile(ECPublicKey publicKey, String path) throws FileNotFoundException, IOException
    {
        String encodedKey = "-----BEGIN EC PUBLIC KEY-----\n";
        try (FileOutputStream stream = new FileOutputStream(path)) {
            encodedKey += Base64.getEncoder().encodeToString(publicKey.serialize());
            encodedKey += "\n-----END EC PUBLIC KEY-----";
            stream.write(encodedKey.getBytes());
        }
    }

    public static byte[] decodePublicKeyfromFile(String path) throws IOException, InvalidKeyException
    {
        System.out.println(path);
        List<String> encodedKeyList = Files.readAllLines(Paths.get(path.trim()));

        if (encodedKeyList.size() != 3) {
            throw new InvalidKeyException("Error: Invalid Public Key Length");
        }

        if ((true == encodedKeyList.get(0).equals("-----BEGIN EC PUBLIC KEY-----")) &&
        (true == encodedKeyList.get(2).equals("-----END EC PUBLIC KEY-----"))) {
            return Base64.getDecoder().decode(encodedKeyList.get(1));
        }

        throw new InvalidKeyException("Error: Invalid Public Key Format");
    }

    public static InMemorySignalProtocolStore createInMemorySignalProtocolStore()
    {
        ECKeyPair tIdentityKeyPairKeys = Curve.generateKeyPair();
        
        IdentityKeyPair tIdentityKeyPair = new IdentityKeyPair(new IdentityKey(tIdentityKeyPairKeys.getPublicKey()),
                                                       tIdentityKeyPairKeys.getPrivateKey());
        
        return new InMemorySignalProtocolStore(tIdentityKeyPair, KeyHelper.generateRegistrationId(false));
    }

    public static boolean verifySignature(byte[] message, ECPublicKey publicKey, String signaturePath) throws InvalidKeyException, IOException
    {
        byte[] encodedsignature = Files.readAllBytes(Paths.get(signaturePath));
        byte[] signature = Base64.getDecoder().decode(encodedsignature);
        
        return Curve.verifySignature(publicKey, message, signature);
    }
    
    public static String getClientID(String clientKeyPath) throws NoSuchAlgorithmException, IOException, InvalidKeyException
    {
        byte[] clientIdentityKey = decodePublicKeyfromFile(clientKeyPath + "clientIdentity.pub");
        return generateID(clientIdentityKey);
    }
}