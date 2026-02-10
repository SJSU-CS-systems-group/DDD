package net.discdd.bundlesecurity;

import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ecc.ECPrivateKey;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.state.impl.InMemorySignalProtocolStore;
import org.whispersystems.libsignal.util.KeyHelper;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static net.discdd.bundlesecurity.DDDPEMEncoder.decodeEncryptedPublicKeyfromFile;
import static net.discdd.bundlesecurity.DDDPEMEncoder.decodePublicKeyfromFile;

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
    public static final String EC_ENCRYPTED_PUBLIC_KEY_HEADER = "-----BEGIN EC PRIVATE KEY-----";
    public static final String EC_ENCRYPTED_PUBLIC_KEY_FOOTER = "-----END EC PRIVATE KEY-----";

    // ----------------- TransportSecurity -----------------
    public static final String TRANSPORT_KEY_PATH = "Transport_Keys";
    public static final String TRANSPORT = "transport";

    // ----------------- ClientSecurity -----------------
    public static final String CLIENT = "client";
    public static final String SESSION_STORE_FILE = "Session.store";
    public static final String CLIENT_KEY_PATH = "Client_Keys";
    public static final String CLIENT_IDENTITY_KEY = "clientIdentity.pub";
    public static final String CLIENT_IDENTITY_PRIVATE_KEY = "clientIdentity.pvt";
    public static final String CLIENT_BASE_KEY = "clientBase.pub";
    public static final String CLIENT_BASE_PRIVATE_KEY = "clientBase.pvt";

    // ----------------- ServerSecurity -----------------
    public static final String SERVER = "server";
    public static final String SERVER_KEY_PATH = "Server_Keys";
    public static final String SERVER_IDENTITY_KEY = "server_identity.pub";
    public static final String SERVER_SIGNED_PRE_KEY = "server_signed_pre.pub";
    public static final String SERVER_RATCHET_KEY = "server_ratchet.pub";
    public static final String SERVER_IDENTITY_PRIVATE_KEY = "serverIdentity.pvt";
    public static final String SERVER_SIGNEDPRE_PRIVATE_KEY = "serverSignedPreKey.pvt";
    public static final String SERVER_RATCHET_PRIVATE_KEY = "serverRatchetKey.pvt";

    public static final String ADAPTER = "adapter";

    // ----------------- GrpcSecurity -----------------
    public static final String GRPC_SECURITY_PATH = "GrpcSecurity";
    public static final String GRPC_PUBLIC_KEY = "%sGrpcPub.pub";
    public static final String GRPC_PRIVATE_KEY = "%sGrpcPvt.pvt";
    public static final String GRPC_CERT = "%sGrpcCert.crt";

    // ----------------- Other Utils -----------------
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
    public static String generateID(Path publicKeyPath) throws IOException, InvalidKeyException {
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
    public static String generateID(byte[] publicKey) {
        try {
            return Base64.getUrlEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(publicKey));
        } catch (NoSuchAlgorithmException e) {
            logger.log(SEVERE, "SHA-1 algorithm not found. Exiting", e);
            System.exit(2);
            return null;
        }
    }

    public static String generateID(String publicKeyBase64) {
        byte[] publicKeyBytes = Base64.getUrlDecoder().decode(publicKeyBase64);
        return generateID(publicKeyBytes);
    }

    public static InMemorySignalProtocolStore createInMemorySignalProtocolStore() {
        ECKeyPair tIdentityKeyPairKeys = Curve.generateKeyPair();

        IdentityKeyPair tIdentityKeyPair = new IdentityKeyPair(new IdentityKey(tIdentityKeyPairKeys.getPublicKey()),
                                                               tIdentityKeyPairKeys.getPrivateKey());

        return new InMemorySignalProtocolStore(tIdentityKeyPair, KeyHelper.generateRegistrationId(false));
    }

    public static boolean verifySignatureRaw(byte[] message, ECPublicKey publicKey, byte[] signature) throws
            InvalidKeyException {
        return Curve.verifySignature(publicKey, message, signature);
    }

    public static byte[] signMessageRaw(byte[] message, ECPrivateKey signingKey) throws InvalidKeyException {
        return Curve.calculateSignature(signingKey, message);
    }

    public static String getClientID(Path bundlePath) throws IOException, InvalidKeyException,
            NoSuchAlgorithmException {
        ServerSecurity serverSecurityInstance = ServerSecurity.getInstance(bundlePath.getParent());
        ECPrivateKey ServerPrivKey = serverSecurityInstance.getSigningKey();
        String publicKey = decodeEncryptedPublicKeyfromFile(ServerPrivKey, bundlePath.resolve(CLIENT_IDENTITY_KEY));
        return generateID(publicKey);
    }

    // Deterministic encryption: derives the IV from HMAC(sharedSecret, plainText) so that
    // encrypting the same plaintext with the same key always yields the same ciphertext.
    // Used for bundle ID encryption where client and server must independently compute matching IDs.
    public static String encryptAesCbcPkcs5Deterministic(String sharedSecret, String plainText) throws
            NoSuchAlgorithmException, InvalidKeySpecException, NoSuchPaddingException,
            InvalidAlgorithmParameterException, java.security.InvalidKeyException, IllegalBlockSizeException,
            BadPaddingException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] iv = Arrays.copyOf(mac.doFinal(plainText.getBytes(StandardCharsets.UTF_8)), 16);

        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(sharedSecret.toCharArray(), sharedSecret.getBytes(), ITERATIONS, KEYLEN);
        SecretKey skey = factory.generateSecret(spec);
        SecretKeySpec secretKeySpec = new SecretKeySpec(skey.getEncoded(), "AES");

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, new IvParameterSpec(iv));

        byte[] encryptedData = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

        byte[] combined = new byte[iv.length + encryptedData.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encryptedData, 0, combined, iv.length, encryptedData.length);

        return Base64.getUrlEncoder().encodeToString(combined);
    }

    public static String encryptAesCbcPkcs5(String sharedSecret, String plainText) throws NoSuchAlgorithmException,
            InvalidKeySpecException, NoSuchPaddingException, InvalidAlgorithmParameterException,
            java.security.InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        SecureRandom random = new SecureRandom();
        byte[] iv = new byte[16];
        random.nextBytes(iv);

        /* Create SecretKeyFactory object */
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");

        /* Create KeySpec object */
        KeySpec spec = new PBEKeySpec(sharedSecret.toCharArray(), sharedSecret.getBytes(), ITERATIONS, KEYLEN);
        SecretKey skey = factory.generateSecret(spec);
        SecretKeySpec secretKeySpec = new SecretKeySpec(skey.getEncoded(), "AES");

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, new IvParameterSpec(iv));

        byte[] encryptedData = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

        // Prepend IV to ciphertext so decrypt can recover it
        byte[] combined = new byte[iv.length + encryptedData.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encryptedData, 0, combined, iv.length, encryptedData.length);

        return Base64.getUrlEncoder().encodeToString(combined);
    }

    public static byte[] decryptAesCbcPkcs5(String sharedSecret, String cipherText) throws GeneralSecurityException {
        byte[] allData = Base64.getUrlDecoder().decode(cipherText);

        /* Create SecretKeyFactory object */
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        /* Create KeySpec object */
        KeySpec spec = new PBEKeySpec(sharedSecret.toCharArray(), sharedSecret.getBytes(), ITERATIONS, KEYLEN);
        SecretKey skey = factory.generateSecret(spec);
        SecretKeySpec secretKeySpec = new SecretKeySpec(skey.getEncoded(), "AES");

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");

        // New format: first 16 bytes are the IV, remainder is ciphertext
        byte[] iv = Arrays.copyOfRange(allData, 0, 16);
        byte[] encryptedData = Arrays.copyOfRange(allData, 16, allData.length);

        try {
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, new IvParameterSpec(iv));
            return cipher.doFinal(encryptedData);
        } catch (BadPaddingException e) {
            // Fallback for legacy data encrypted with a zero IV
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, new IvParameterSpec(new byte[16]));
            return cipher.doFinal(allData);
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