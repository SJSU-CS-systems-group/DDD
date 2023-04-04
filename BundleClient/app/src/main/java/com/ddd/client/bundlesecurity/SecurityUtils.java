package ddd;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.InvalidAlgorithmParameterException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Base64;
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

public class SecurityUtils {
    public static final String PAYLOAD_FILENAME = "payload";
    public static final String SIGN_FILENAME = PAYLOAD_FILENAME + ".signature";
    public static final String BUNDLEID_FILENAME = "bundle.id";
    public static final String DECRYPTED_FILE_EXT = ".decrypted";
    public static final int ITERATIONS = 65536;
    public static final int KEYLEN     = 256;

    public static class ClientSession {
        String          clientID;
        IdentityKey     IdentityKey;
        ECPublicKey     BaseKey;

        SessionCipher   cipherSession;
        SessionRecord   serverSessionRecord;
    };

    public static class EncryptAesCbcPkcs5 {
        String encryptedData;
        String salt;

        public byte[] getSalt()
        {
            SecureRandom random = new SecureRandom();
            byte[] salt = new byte[20];
            random.nextBytes(salt);
            return salt;
        }
    }
    
    public static String generateID(String publicKeyPath) throws NoSessionException, IOException, InvalidKeyException, NoSuchAlgorithmException
    {
        byte[] publicKey = decodePublicKeyfromFile(publicKeyPath);
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] hashedKey = md.digest(publicKey);
        return Base64.getUrlEncoder().encodeToString(hashedKey);
    }

    public static String generateID(byte[] publicKey) throws NoSuchAlgorithmException
    {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] hashedKey = md.digest(publicKey);
        return Base64.getUrlEncoder().encodeToString(hashedKey);
    }

    public static void createEncodedPublicKeyFile(ECPublicKey publicKey, String path) throws FileNotFoundException, IOException
    {
        String encodedKey = "-----BEGIN EC PUBLIC KEY-----\n";
        try (FileOutputStream stream = new FileOutputStream(path)) {
            encodedKey += Base64.getUrlEncoder().encodeToString(publicKey.serialize());
            encodedKey += "\n-----END EC PUBLIC KEY-----";
            stream.write(encodedKey.getBytes());
        }
    }

    public static byte[] decodePublicKeyfromFile(String path) throws IOException, InvalidKeyException
    {
        List<String> encodedKeyList = Files.readAllLines(Paths.get(path.trim()));

        if (encodedKeyList.size() != 3) {
            throw new InvalidKeyException("Error: Invalid Public Key Length");
        }

        if ((true == encodedKeyList.get(0).equals("-----BEGIN EC PUBLIC KEY-----")) &&
        (true == encodedKeyList.get(2).equals("-----END EC PUBLIC KEY-----"))) {
            return Base64.getUrlDecoder().decode(encodedKeyList.get(1));
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
        byte[] signature = Base64.getUrlDecoder().decode(encodedsignature);
        
        return Curve.verifySignature(publicKey, message, signature);
    }
    
    public static String getClientID(String clientKeyPath) throws NoSuchAlgorithmException, IOException, InvalidKeyException
    {
        byte[] clientIdentityKey = decodePublicKeyfromFile(clientKeyPath + "clientIdentity.pub");
        return generateID(clientIdentityKey);
    }

    public static EncryptAesCbcPkcs5 encryptAesCbcPkcs5(String sharedSecret, String plainText) throws NoSuchAlgorithmException, InvalidKeySpecException, NoSuchPaddingException, java.security.InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException
    {
        EncryptAesCbcPkcs5 encData = new EncryptAesCbcPkcs5();
        byte[] salt = encData.getSalt();
        byte[] iv = new byte[16];
        
        /* Create SecretKeyFactory object */
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        /* Create KeySpec object */
        KeySpec spec = new PBEKeySpec(sharedSecret.toCharArray(), salt, ITERATIONS, KEYLEN);
        SecretKey skey = factory.generateSecret(spec);
        SecretKeySpec secretKeySpec = new SecretKeySpec(skey.getEncoded(), "AES");

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, new IvParameterSpec(iv));
        
        byte[] encryptedData = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        encData.encryptedData = Base64.getUrlEncoder().encodeToString(encryptedData);
        encData.salt = Base64.getUrlEncoder().encodeToString(salt);

        return encData;
    }

    public static byte[] dencryptAesCbcPkcs5(String sharedSecret, String cipherText, String saltStr) throws NoSuchAlgorithmException, InvalidKeySpecException, java.security.InvalidKeyException, InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException
    {
        byte[] iv = new byte[16];
        byte[] salt = Base64.getUrlDecoder().decode(saltStr);
        byte[] encryptedData = Base64.getUrlDecoder().decode(cipherText);

        /* Create SecretKeyFactory object */
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        /* Create KeySpec object */
        KeySpec spec = new PBEKeySpec(sharedSecret.toCharArray(), salt, ITERATIONS, KEYLEN);
        SecretKey skey = factory.generateSecret(spec);
        SecretKeySpec secretKeySpec = new SecretKeySpec(skey.getEncoded(), "AES");

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, new IvParameterSpec(iv));

        return cipher.doFinal(encryptedData);
    }
}