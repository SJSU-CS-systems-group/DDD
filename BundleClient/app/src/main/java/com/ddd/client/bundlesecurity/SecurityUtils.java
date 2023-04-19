package com.ddd.client.bundlesecurity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.InvalidAlgorithmParameterException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.List;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

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

import com.ddd.client.bundlesecurity.SecurityExceptions.IDGenerationException;
import com.ddd.client.bundlesecurity.SecurityExceptions.EncodingException;
import com.ddd.client.bundlesecurity.SecurityExceptions.SignatureVerificationException;
import com.ddd.client.bundlesecurity.SecurityExceptions.AESAlgorithmException;
import android.util.Base64;

import com.ddd.datastore.filestore.FileStoreHelper;

public class SecurityUtils {
    public static final String PAYLOAD_FILENAME     = "payload";
    public static final String SIGNATURE_FILENAME   = ".signature";
    public static final String PAYLOAD_DIR          = "payloads";
    public static final String SIGNATURE_DIR        = "signatures";
    public static final String SIGN_FILENAME        = PAYLOAD_FILENAME + ".signature";
    public static final String BUNDLEID_FILENAME    = "bundle.id";
    public static final String DECRYPTED_FILE_EXT   = ".decrypted";
    public static final String PUBLICKEY_HEADER    = "-----BEGIN EC PUBLIC KEY-----";
    public static final String PUBLICKEY_FOOTER    = "-----END EC PUBLIC KEY-----";
    
    public static final int CHUNKSIZE  = 1024 * 1024; /* 1MB */
    public static final int ITERATIONS = 65536;
    public static final int KEYLEN     = 256;

    public static class ClientSession {
        String          clientID;
        IdentityKey     IdentityKey;
        ECPublicKey     BaseKey;

        SessionCipher   cipherSession;
        SessionRecord   serverSessionRecord;
    };

    /* Creates an ID based on the given public key file
     * Generates a SHA-1 hash and then encodes it in Base64 (URL safe)
     * Parameters:
     * publicKeyPath    :The path to the public key file
     * Returns:
     * The generated ID as a string
     */
    public static String generateID(String publicKeyPath) throws IDGenerationException
    {
        String id = null;
        try {
            byte[] publicKey = decodePublicKeyfromFile(publicKeyPath);
            id = generateID(publicKey);
        } catch (Exception e) {
            throw new IDGenerationException("Failed to generateID: "+e);
        }
        return id;
    }

    /* Creates an ID based on the given public key byte array
     * Generates a SHA-1 hash and then encodes it in Base64 (URL safe)
     * Parameters:
     * publicKey    : Byte array with the public key
     * Returns:
     * The generated ID as a string
     */
    public static String generateID(byte[] publicKey) throws IDGenerationException
    {
        MessageDigest md;

        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IDGenerationException("[BS]: NoSuchAlgorithmException while generating ID");
        }

        byte[] hashedKey = md.digest(publicKey);
        return Base64.encodeToString(hashedKey, Base64.URL_SAFE | Base64.NO_WRAP);
    }

    public static void createEncodedPublicKeyFile(ECPublicKey publicKey, String path) throws EncodingException 
    {
        String encodedKey = PUBLICKEY_HEADER+"\n";
        try (FileOutputStream stream = new FileOutputStream(path)) {
            encodedKey += Base64.encodeToString(publicKey.serialize(), Base64.URL_SAFE | Base64.NO_WRAP);
            encodedKey += "\n" + PUBLICKEY_FOOTER;
            stream.write(encodedKey.getBytes());
        } catch (IOException e) {
            throw new EncodingException("[BS]: Failed to Encode Public Key to file:"+e);
        }
    }

    public static byte[] decodePublicKeyfromFile(String path) throws EncodingException
    {
        try {
            List<String> encodedKeyList = Files.readAllLines(Paths.get(path.trim()));

            if (encodedKeyList.size() != 3) {
                throw new InvalidKeyException("Error: Invalid Public Key Length");
            }

            if ((true == encodedKeyList.get(0).equals(PUBLICKEY_HEADER)) &&
            (true == encodedKeyList.get(2).equals(PUBLICKEY_FOOTER))) {
                return Base64.decode(encodedKeyArr[1], Base64.URL_SAFE | Base64.NO_WRAP);
            } else {
                throw new InvalidKeyException("Error: Invalid Public Key Format");
            }
        } catch (InvalidKeyException | IOException e) {
            throw new EncodingException("Error: Invalid Public Key Format");
        }
    }

    public static InMemorySignalProtocolStore createInMemorySignalProtocolStore()
    {
        ECKeyPair tIdentityKeyPairKeys = Curve.generateKeyPair();
        
        IdentityKeyPair tIdentityKeyPair = new IdentityKeyPair(new IdentityKey(tIdentityKeyPairKeys.getPublicKey()),
                                                       tIdentityKeyPairKeys.getPrivateKey());
        
        return new InMemorySignalProtocolStore(tIdentityKeyPair, KeyHelper.generateRegistrationId(false));
    }

    public static boolean verifySignature(byte[] message, ECPublicKey publicKey, String signaturePath) throws SignatureVerificationException
    {
        try {
            byte[] encodedsignature = Files.readAllBytes(Paths.get(signaturePath));
            byte[] signature = Base64.decode(encodedsignature, Base64.URL_SAFE | Base64.NO_WRAP);
            
            return Curve.verifySignature(publicKey, message, signature);
        } catch (InvalidKeyException | IOException e) {
            throw new SignatureVerificationException("Error Verifying Signature: "+e);
        }
    }
    
    public static String getClientID(String clientKeyPath) throws IDGenerationException
    {
        byte[] clientIdentityKey;
        try {
            clientIdentityKey = decodePublicKeyfromFile(clientKeyPath + File.separator + "clientIdentity.pub");
        } catch (EncodingException e) {
            throw new IDGenerationException("Error decoding public key file: "+e);
        }
        return generateID(clientIdentityKey);
    }

    public static String encryptAesCbcPkcs5(String sharedSecret, String plainText) throws AESAlgorithmException
    {
        byte[] iv = new byte[16];
        byte[] encryptedData = null;
        
        /* Create SecretKeyFactory object */
        SecretKeyFactory factory;
        try {
            factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");

            /* Create KeySpec object */
            KeySpec spec = new PBEKeySpec(sharedSecret.toCharArray(), sharedSecret.getBytes(), ITERATIONS, KEYLEN);
            SecretKey skey = factory.generateSecret(spec);
            SecretKeySpec secretKeySpec = new SecretKeySpec(skey.getEncoded(), "AES");

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, new IvParameterSpec(iv));
            
            encryptedData = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            
        } catch ( NoSuchAlgorithmException | InvalidKeySpecException | NoSuchPaddingException |
                    java.security.InvalidKeyException | InvalidAlgorithmParameterException |
                    IllegalBlockSizeException | BadPaddingException e) {
            throw new AESAlgorithmException("Error Encrypting text using AES: "+e);
        }
        return Base64.encodeToString(encryptedData, Base64.URL_SAFE | Base64.NO_WRAP);
    }

    public static byte[] dencryptAesCbcPkcs5(String sharedSecret, String cipherText) throws AESAlgorithmException
    {
        byte[] iv = new byte[16];
        byte[] encryptedData = Base64.decode(cipherText, Base64.URL_SAFE | Base64.NO_WRAP);
        byte[] decryptedData = null;

        try {
            /* Create SecretKeyFactory object */
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            /* Create KeySpec object */
            KeySpec spec = new PBEKeySpec(sharedSecret.toCharArray(), sharedSecret.getBytes(), ITERATIONS, KEYLEN);
            SecretKey skey = factory.generateSecret(spec);
            SecretKeySpec secretKeySpec = new SecretKeySpec(skey.getEncoded(), "AES");

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, new IvParameterSpec(iv));

            decryptedData = cipher.doFinal(encryptedData);
        } catch(NoSuchAlgorithmException | InvalidKeySpecException | NoSuchPaddingException |
                    java.security.InvalidKeyException | InvalidAlgorithmParameterException |
                    IllegalBlockSizeException | BadPaddingException e) {
            throw new AESAlgorithmException("Error Decrypting text using AES: ");
        }
        return decryptedData;
    }

    public static byte[] readFromFile(String filePath) throws FileNotFoundException, IOException
    {
        File file = new File(filePath);
        byte[] bytes = new byte[(int) file.length()];

        try(FileInputStream fis = new FileInputStream(file)) {
            fis.read(bytes);
        }
        return bytes;
    }

    public static void createDirectory(String dirPath)
    {
        File file = new File(dirPath);
        if (!file.exists()) {
            file.mkdirs();
        }
    }
}