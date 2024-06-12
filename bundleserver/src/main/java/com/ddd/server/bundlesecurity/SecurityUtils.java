package com.ddd.server.bundlesecurity;

import com.ddd.bundlesecurity.SecurityExceptions.AESAlgorithmException;
import com.ddd.bundlesecurity.SecurityExceptions.EncodingException;
import com.ddd.bundlesecurity.SecurityExceptions.IDGenerationException;
import com.ddd.bundlesecurity.SecurityExceptions.SignatureVerificationException;

import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.state.impl.InMemorySignalProtocolStore;
import org.whispersystems.libsignal.util.KeyHelper;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.InvalidAlgorithmParameterException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class SecurityUtils {
    public static final String PAYLOAD_FILENAME = "payload";
    public static final String SIGNATURE_FILENAME = ".signature";
    public static final String PAYLOAD_DIR = "payloads";
    public static final String SIGNATURE_DIR = "signatures";
    public static final String SIGN_FILENAME = PAYLOAD_FILENAME + ".signature";
    public static final String BUNDLEID_FILENAME = "bundle.id";
    public static final String DECRYPTED_FILE_EXT = ".decrypted";
    public static final String BUNDLE_FILE_EXT = ".bundle";

    public static final String PUB_KEY_HEADER = "-----BEGIN EC PUBLIC KEY-----";
    public static final String PUB_KEY_FOOTER = "-----END EC PUBLIC KEY-----";
    public static final String PVT_KEY_HEADER = "-----BEGIN EC PRIVATE KEY-----";
    public static final String PVT_KEY_FOOTER = "-----END EC PRIVATE KEY-----";

    public static final String CLIENT_KEY_PATH = "Client_Keys";
    public static final String SERVER_KEY_PATH = "Server_Keys";
    public static final String SESSION_STORE_FILE = "Session.store";

    public static final String CLIENT_IDENTITY_KEY = "clientIdentity.pub";
    public static final String CLIENT_BASE_KEY = "clientBase.pub";

    public static final String SERVER_IDENTITY_KEY = "server_identity.pub";
    public static final String SERVER_SIGNEDPRE_KEY = "server_signed_pre.pub";
    public static final String SERVER_RATCHET_KEY = "server_ratchet.pub";
    public static final String SERVER_IDENTITY_PRIVATE_KEY = "serverIdentity.pvt";
    public static final String SERVER_SIGNEDPRE_PRIVATE_KEY = "serverSignedPreKey.pvt";
    public static final String SERVER_RATCHET_PRIVATE_KEY = "serverRatchetKey.pvt";

    public static final int CHUNKSIZE = 1024 * 1024; /* 1MB */
    public static final int ITERATIONS = 65536;
    public static final int KEYLEN = 256;

    public static class ClientSession {
        SignalProtocolAddress clientProtocolAddress;
        IdentityKey IdentityKey;
        ECPublicKey BaseKey;

        SessionCipher cipherSession;

        public String getClientID() {
            return this.clientProtocolAddress.getName();
        }
    }

    /*
     * Creates an ID based on the given public key file
     * Generates a SHA-1 hash and then encodes it in Base64 (URL safe)
     * Parameters:
     * publicKeyPath :The path to the public key file
     * Returns:
     * The generated ID as a string
     */
    public static String generateID(String publicKeyPath) throws IDGenerationException {
        String id = null;
        try {
            byte[] publicKey = decodePublicKeyfromFile(publicKeyPath);
            id = generateID(publicKey);
        } catch (Exception e) {
            throw new IDGenerationException("Failed to generateID ", e);
        }
        return id;
    }

    /*
     * Creates an ID based on the given public key byte array
     * Generates a SHA-1 hash and then encodes it in Base64 (URL safe)
     * Parameters:
     * publicKey : Byte array with the public key
     * Returns:
     * The generated ID as a string
     */
    public static String generateID(byte[] publicKey) throws IDGenerationException {
        MessageDigest md;

        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IDGenerationException("[BS]: NoSuchAlgorithmException while generating ID ", e);
        }

        byte[] hashedKey = md.digest(publicKey);
        return Base64.getUrlEncoder().encodeToString(hashedKey);
    }

    public static void createEncodedPublicKeyFile(ECPublicKey publicKey, String path) throws EncodingException {
        String encodedKey = PUB_KEY_HEADER + "\n";
        try (FileOutputStream stream = new FileOutputStream(path, false)) {
            encodedKey += Base64.getUrlEncoder().encodeToString(publicKey.serialize());
            encodedKey += "\n" + PUB_KEY_FOOTER;
            stream.write(encodedKey.getBytes());
        } catch (IOException e) {
            throw new EncodingException("[BS]: Failed to Encode Public Key to file: ", e);
        }
    }

    public static byte[] decodePublicKeyfromFile(String path) throws EncodingException {
        try {
            List<String> encodedKeyList = Files.readAllLines(Paths.get(path.trim()));

            if (encodedKeyList.size() != 3) {
                throw new InvalidKeyException("Error: Invalid Public Key Length");
            }

            if (encodedKeyList.get(0).equals(PUB_KEY_HEADER) && encodedKeyList.get(2).equals(PUB_KEY_FOOTER)) {
                return Base64.getUrlDecoder().decode(encodedKeyList.get(1));
            } else {
                throw new InvalidKeyException("Error: Invalid Public Key Format");
            }
        } catch (InvalidKeyException | IOException e) {
            e.printStackTrace();
            throw new EncodingException("Error: Invalid Public Key Format ", e);
        }
    }

    public static byte[] decodePrivateKeyFromFile(String path) throws EncodingException {
        try {
            List<String> encodedKeyList = Files.readAllLines(Paths.get(path.trim()));

            if (encodedKeyList.size() != 3) {
                throw new InvalidKeyException("Error: Invalid Public Key Length");
            }

            if (encodedKeyList.get(0).equals(PVT_KEY_HEADER) && encodedKeyList.get(2).equals(PVT_KEY_FOOTER)) {
                return Base64.getUrlDecoder().decode(encodedKeyList.get(1));
            } else {

                throw new InvalidKeyException("Error: Invalid Public Key Format");
            }
        } catch (InvalidKeyException | IOException e) {
            e.printStackTrace();
            throw new EncodingException("Error: Invalid Public Key Format ", e);
        }

    }

    public static InMemorySignalProtocolStore createInMemorySignalProtocolStore() {
        ECKeyPair tIdentityKeyPairKeys = Curve.generateKeyPair();

        IdentityKeyPair tIdentityKeyPair = new IdentityKeyPair(new IdentityKey(tIdentityKeyPairKeys.getPublicKey()),
                                                               tIdentityKeyPairKeys.getPrivateKey());

        return new InMemorySignalProtocolStore(tIdentityKeyPair, KeyHelper.generateRegistrationId(false));
    }

    public static boolean verifySignature(byte[] message, ECPublicKey publicKey, String signaturePath) throws SignatureVerificationException {
        try {
            byte[] encodedsignature = SecurityUtils.readFromFile(signaturePath);
            byte[] signature = Base64.getUrlDecoder().decode(encodedsignature);

            return Curve.verifySignature(publicKey, message, signature);
        } catch (InvalidKeyException | IOException e) {

            throw new SignatureVerificationException("Error Verifying Signature: ", e);
        }
    }

    public static String getClientID(String bundlePath) throws IDGenerationException {
        byte[] clientIdentityKey;
        try {
            clientIdentityKey = decodePublicKeyfromFile(bundlePath + File.separator + CLIENT_IDENTITY_KEY);
        } catch (EncodingException e) {
            e.printStackTrace();
            throw new IDGenerationException("Error getting Client ID: ", e);
        }
        return generateID(clientIdentityKey);
    }

    public static String encryptAesCbcPkcs5(String sharedSecret, String plainText) throws AESAlgorithmException {
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

        } catch (NoSuchAlgorithmException | InvalidKeySpecException | NoSuchPaddingException |
                 java.security.InvalidKeyException | InvalidAlgorithmParameterException | IllegalBlockSizeException |
                 BadPaddingException e) {
            throw new AESAlgorithmException("Error Encrypting text using AES: ", e);
        }
        return Base64.getUrlEncoder().encodeToString(encryptedData);
    }

    public static byte[] decryptAesCbcPkcs5(String sharedSecret, String cipherText) throws AESAlgorithmException {
        byte[] iv = new byte[16];
        byte[] encryptedData = Base64.getUrlDecoder().decode(cipherText);
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
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | NoSuchPaddingException |
                 java.security.InvalidKeyException | InvalidAlgorithmParameterException | IllegalBlockSizeException |
                 BadPaddingException e) {
            throw new AESAlgorithmException("Error Decrypting text [" + cipherText + "] using AES: ", e);
        }
        return decryptedData;
    }

    public static byte[] readFromFile(String filePath) throws FileNotFoundException, IOException {
        File file = new File(filePath);
        byte[] bytes = new byte[(int) file.length()];

        try (FileInputStream fis = new FileInputStream(file)) {
            fis.read(bytes);
        }
        return bytes;
    }

    public static void createDirectory(String dirPath) {
        File file = new File(dirPath);
        if (!file.exists()) {
            file.mkdirs();
        }
    }

    public static void copyContent(InputStream in, OutputStream out) throws IOException {
        int data = -1;

        while ((data = in.read()) != -1) {
            out.write(data);
        }
    }

    public static String unzip(String zipFilePath) throws IOException {

        File zipFile = new File(zipFilePath);
        String destDirPath = zipFile.getParent() + File.separator + zipFile.getName().replaceFirst("[.][^.]+$", "");
        File destDir = new File(destDirPath);

        try (FileInputStream fis = new FileInputStream(zipFilePath); ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry entry = zis.getNextEntry();
            while (entry != null) {
                File newFile = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    File parent = newFile.getParentFile();
                    if (!parent.exists()) {
                        parent.mkdirs();
                    }

                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
                entry = zis.getNextEntry();
            }

            System.out.println("Unzipped to: " + destDir.getAbsolutePath());

            return destDir.getAbsolutePath();
        } catch (IOException e) {
            throw e;
        }
    }
}