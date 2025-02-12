package net.discdd.bundlesecurity;

import org.whispersystems.libsignal.*;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ecc.ECPrivateKey;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.state.impl.InMemorySignalProtocolStore;
import org.whispersystems.libsignal.util.KeyHelper;

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.util.logging.Level.INFO;

public class SecurityUtils {
    public static final String PAYLOAD_FILENAME = "payload";
    public static final String PAYLOAD_DIR = "payloads";
    public static final String BUNDLEID_FILENAME = "bundle.id";
    public static final String DECRYPTED_FILE_EXT = ".decrypted";
    public static final String BUNDLE_SECURITY_DIR = "BundleSecurity";
    public static final String PUB_KEY_HEADER = "-----BEGIN EC PUBLIC KEY-----";
    public static final String PUB_KEY_FOOTER = "-----END EC PUBLIC KEY-----";
    public static final String PVT_KEY_HEADER = "-----BEGIN EC PRIVATE KEY-----";
    public static final String PVT_KEY_FOOTER = "-----END EC PRIVATE KEY-----";
    public static final String CLIENT_KEY_PATH = "Client_Keys";
    public static final String SERVER_KEY_PATH = "Server_Keys";
    public static final String TRANSPORT_KEY_PATH = "Transport_Keys";
    public static final String TRANSPORT_IDENTITY_KEY = "transport_identity.pub";
    public static final String TRANSPORT_IDENTITY_PRIVATE_KEY = "transportIdentity.pvt";
    public static final String SESSION_STORE_FILE = "Session.store";
    public static final String CLIENT_IDENTITY_KEY = "clientIdentity.pub";
    public static final String CLIENT_IDENTITY_PRIVATE_KEY = "clientIdentity.pvt";
    public static final String CLIENT_BASE_KEY = "clientBase.pub";
    public static final String CLIENT_BASE_PRIVATE_KEY = "clientBase.pvt";
    public static final String SERVER_IDENTITY_KEY = "server_identity.pub";
    public static final String SERVER_SIGNED_PRE_KEY = "server_signed_pre.pub";
    public static final String SERVER_RATCHET_KEY = "server_ratchet.pub";
    public static final String SERVER_IDENTITY_PRIVATE_KEY = "serverIdentity.pvt";
    public static final String SERVER_SIGNEDPRE_PRIVATE_KEY = "serverSignedPreKey.pvt";
    public static final String SERVER_RATCHET_PRIVATE_KEY = "serverRatchetKey.pvt";
    public static final int CHUNKSIZE = 1024 * 1024; /* 1MB */
    public static final int ITERATIONS = 65536;
    public static final int KEYLEN = 256;
    private static final Logger logger = Logger.getLogger(SecurityUtils.class.getName());

    /* Creates an ID based on the given public key file
     * Generates a SHA-1 hash and then encodes it in Base64 (URL safe)
     * Parameters:
     * publicKeyPath    :The path to the public key file
     * Returns:
     * The generated ID as a string
     */
    public static String generateID(Path publicKeyPath) throws IOException, InvalidKeyException,
            NoSuchAlgorithmException {
        byte[] publicKey = decodePublicKeyfromFile(publicKeyPath);
        return generateID(publicKey);
    }

    /* Creates an ID based on the given public key byte array
     * Generates a SHA-1 hash and then encodes it in Base64 (URL safe)
     * Parameters:
     * publicKey    : Byte array with the public key
     * Returns:
     * The generated ID as a string
     */
    public static String generateID(byte[] publicKey) throws NoSuchAlgorithmException {
        return Base64.getUrlEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(publicKey));
    }

    public static void createEncodedPublicKeyFile(ECPublicKey publicKey, Path path) throws IOException {
        try {
            Files.write(path, createEncodedPublicKeyBytes(publicKey, path), StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException(e);
        } catch (InvalidKeyException e) {
            throw new RuntimeException(e);
        }

    }

    public static byte[] createEncodedPublicKeyBytes(ECPublicKey publicKey, Path serverRootPath) throws GeneralSecurityException, InvalidKeyException {
        // create ephemeral public key pair
        ECKeyPair ephemeralKeyPair = Curve.generateKeyPair();
        // calculate shared secret from server public key and ephemeral private key
        // Unless testing, we're passing null for path b/c server security instance will already be created
        ServerSecurity serverSecurityInstance = ServerSecurity.getInstance(serverRootPath);
        IdentityKey ServerPubKey = serverSecurityInstance.getIdentityPublicKey();
        byte[] agreement = Curve.calculateAgreement((ECPublicKey) ServerPubKey, ephemeralKeyPair.getPrivateKey());
        String sharedSecret = Base64.getUrlEncoder().encodeToString(agreement);
        // encrypt client public key using shared secret
        String encryptedClientPubKey = encryptAesCbcPkcs5(sharedSecret, publicKey.toString());
        return (PUB_KEY_HEADER + "\n" +
                // write encrypted client public key to file
                Base64.getUrlEncoder().encode(encryptedClientPubKey.getBytes()) + "\n" +
                // write ephemeral public key to file
                Base64.getUrlEncoder().encode(ephemeralKeyPair.getPublicKey().toString().getBytes()) + "\n" +
                PUB_KEY_FOOTER).getBytes();
    }

    public static byte[] decodePublicKeyfromFile(Path path) throws IOException, InvalidKeyException {
        List<String> encodedKeyList = Files.readAllLines(path);
        if (encodedKeyList.size() != 4) {
            throw new InvalidKeyException(
                    String.format("Error: %s should have four lines: HEADER, KEY, SECRET, FOOTER", path.getFileName()));
        }
        if ((encodedKeyList.get(0).equals(PUB_KEY_HEADER)) && (encodedKeyList.get(3).equals(PUB_KEY_FOOTER))) {
            // read encrypted client public key
            byte[] encryptedClientPublicKey = Base64.getUrlDecoder().decode(encodedKeyList.get(1)); //should work b/c we wrote keys and next line
            // read ephemeral public key
            byte[] ephemeralKey = Base64.getUrlDecoder().decode(encodedKeyList.get(2));
            KeyFactory kf = null;
            PublicKey ephemeralPublicKey;
            try {
                kf = KeyFactory.getInstance("EC");
                ephemeralPublicKey = kf.generatePublic(new X509EncodedKeySpec(ephemeralKey));
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("EC algorithm is not available in the Java environment.", e);
            } catch (InvalidKeySpecException e) {
                throw new RuntimeException("Invalid EC public key provided. Ensure the key encoding is correct.", e);
            }
            // calculate shared secret from server private key and ephemeral public key
            ServerSecurity serverSecurityInstance = ServerSecurity.getInstance(path);
            ECPrivateKey ServerPrivKey = serverSecurityInstance.getSigningKey();
            byte[] agreement = Curve.calculateAgreement((ECPublicKey) ephemeralPublicKey, ServerPrivKey);
            String sharedSecret = Base64.getUrlEncoder().encodeToString(agreement);
            // decrypt client public key using shared secret
            byte[] decryptedClientPubKey = new byte[0];
            try {
                decryptedClientPubKey = decryptAesCbcPkcs5(sharedSecret, Arrays.toString(encryptedClientPublicKey));
            } catch (GeneralSecurityException e) {
                throw new RuntimeException("AES decryption failed: " + e.getMessage(), e);
            }
            // return decrypted client public key
            return Base64.getUrlDecoder().decode(decryptedClientPubKey);
        } else {
            throw new InvalidKeyException(
                    String.format("Error: %s has invalid public key header or footer", path.getFileName()));
        }
    }

    public static byte[] decodePrivateKeyFromFile(Path path) throws IOException, InvalidKeyException {
        List<String> encodedKeyList = Files.readAllLines(path);

        if (encodedKeyList.size() != 3) {
            throw new InvalidKeyException(
                    String.format("Error: %s should have three lines: HEADER, KEY, FOOTER", path.getFileName()));
        }

        if (encodedKeyList.get(0).equals(PVT_KEY_HEADER) && encodedKeyList.get(2).equals(PVT_KEY_FOOTER)) {
            return Base64.getUrlDecoder().decode(encodedKeyList.get(1));
        } else {
            throw new InvalidKeyException(
                    String.format("Error: %s has invalid private key header or footer", path.getFileName()));
        }
    }

    public static InMemorySignalProtocolStore createInMemorySignalProtocolStore() {
        ECKeyPair tIdentityKeyPairKeys = Curve.generateKeyPair();

        IdentityKeyPair tIdentityKeyPair = new IdentityKeyPair(new IdentityKey(tIdentityKeyPairKeys.getPublicKey()),
                                                               tIdentityKeyPairKeys.getPrivateKey());

        return new InMemorySignalProtocolStore(tIdentityKeyPair, KeyHelper.generateRegistrationId(false));
    }

    public static boolean verifySignatureRaw(byte[] message, ECPublicKey publicKey, byte[] signature) throws InvalidKeyException {
        return Curve.verifySignature(publicKey, message, signature);
    }

    public static byte[] signMessageRaw(byte[] message, ECPrivateKey signingKey) throws InvalidKeyException {
        return Curve.calculateSignature(signingKey, message);
    }

    public static String getClientID(Path bundlePath) throws IOException, InvalidKeyException,
            NoSuchAlgorithmException {
        byte[] clientIdentityKey = decodePublicKeyfromFile(bundlePath.resolve(CLIENT_IDENTITY_KEY));
        return generateID(clientIdentityKey);
    }

    public static String encryptAesCbcPkcs5(String sharedSecret, String plainText) throws NoSuchAlgorithmException,
            InvalidKeySpecException, NoSuchPaddingException, InvalidAlgorithmParameterException,
            java.security.InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        byte[] iv = new byte[16];
        byte[] encryptedData = null;

        /* Create SecretKeyFactory object */
        SecretKeyFactory factory;
        factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");

        /* Create KeySpec object */
        KeySpec spec = new PBEKeySpec(sharedSecret.toCharArray(), sharedSecret.getBytes(), ITERATIONS, KEYLEN);
        SecretKey skey = factory.generateSecret(spec);
        SecretKeySpec secretKeySpec = new SecretKeySpec(skey.getEncoded(), "AES");

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, new IvParameterSpec(iv));

        encryptedData = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

        return Base64.getUrlEncoder().encodeToString(encryptedData);
    }

    public static byte[] decryptAesCbcPkcs5(String sharedSecret, String cipherText) throws GeneralSecurityException {
        byte[] iv = new byte[16];
        byte[] encryptedData = Base64.getUrlDecoder().decode(cipherText);

        /* Create SecretKeyFactory object */
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        /* Create KeySpec object */
        KeySpec spec = new PBEKeySpec(sharedSecret.toCharArray(), sharedSecret.getBytes(), ITERATIONS, KEYLEN);
        SecretKey skey = factory.generateSecret(spec);
        SecretKeySpec secretKeySpec = new SecretKeySpec(skey.getEncoded(), "AES");

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, new IvParameterSpec(iv));

        return cipher.doFinal(encryptedData);
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

            logger.log(INFO, "Unzipped to: " + destDir.getAbsolutePath());

            return destDir.getAbsolutePath();
        }
    }

    public static class ClientSession {
        public SignalProtocolAddress clientProtocolAddress;
        public IdentityKey IdentityKey;
        public ECPublicKey BaseKey;
        public SessionCipher cipherSession;

        public String getClientID() {
            return this.clientProtocolAddress.getName();
        }
    }
}