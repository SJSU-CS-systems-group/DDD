package net.discdd.bundlesecurity;

import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ecc.ECPrivateKey;
import org.whispersystems.libsignal.ecc.ECPublicKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;
import static net.discdd.bundlesecurity.SecurityUtils.decryptAesCbcPkcs5;
import static net.discdd.bundlesecurity.SecurityUtils.encryptAesCbcPkcs5;

/**
 * This class is a bit funky. It uses URL base64 encoding instead of the standard base64 encoding.
 */
public class DDDPEMEncoder {
    public static final String PUB_KEY_HEADER = "-----BEGIN EC PUBLIC KEY-----";
    public static final String PUB_KEY_FOOTER = "-----END EC PUBLIC KEY-----";
    public static final String PVT_KEY_HEADER = "-----BEGIN EC PRIVATE KEY-----";
    public static final String PVT_KEY_FOOTER = "-----END EC PRIVATE KEY-----";
    public static final String EC_ENCRYPTED_PUBLIC_KEY_HEADER = "-----BEGIN EC PRIVATE KEY-----";
    public static final String EC_ENCRYPTED_PUBLIC_KEY_FOOTER = "-----END EC PRIVATE KEY-----";

    private static final Logger logger = Logger.getLogger(DDDPEMEncoder.class.getName());

    private static final String KEY_HEADER = "-----BEGIN %s-----";
    private static final String KEY_FOOTER = "-----END %s-----";

    public static final String ECPrivateKeyType = "EC PRIVATE KEY";
    public static final String ECPublicKeyType = "EC PUBLIC KEY";
    public static final String privateKeyType = "PRIVATE KEY";
    public static final String publicKeyType = "PUBLIC KEY";
    public static final String CERTIFICATE = "CERTIFICATE";

    public static String encode(byte[] bytes, String type) {
        if (type.equals(CERTIFICATE)) {
            return String.format(KEY_HEADER, type) + "\n" + Base64.getEncoder().encodeToString(bytes) + "\n" +
                    String.format(KEY_FOOTER, type);
        }

        return String.format(KEY_HEADER, type) + "\n" + Base64.getUrlEncoder().encodeToString(bytes) + "\n" +
                String.format(KEY_FOOTER, type);
    }

    public static byte[] decodeFromFile(Path path, String type) throws IOException {
        List<String> encodedKeyList = Files.readAllLines(path);

        if (encodedKeyList.size() != 3) {
            logger.log(SEVERE,
                       String.format("Error: %s should have three lines: HEADER, KEY, FOOTER", path.getFileName()));
            return null;
        }

        if (!encodedKeyList.get(0).equals(String.format(KEY_HEADER, type)) ||
                !encodedKeyList.get(2).equals(String.format(KEY_FOOTER, type))) {
            logger.log(SEVERE, String.format("Error: %s has invalid %s header or footer", path.getFileName(), type));
            return null;
        }

        if (type.equals(CERTIFICATE)) {
            return Base64.getDecoder().decode(encodedKeyList.get(1));
        }

        return Base64.getUrlDecoder().decode(encodedKeyList.get(1));
    }

    public static void createEncodedPublicKeyFile(ECPublicKey publicKey, Path path) throws IOException {
        Files.write(path,
                    createEncodedPublicKeyBytes(publicKey),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
    }

    public static byte[] createEncodedPublicKeyBytes(ECPublicKey publicKey) {
        return (PUB_KEY_HEADER + "\n" + Base64.getUrlEncoder().encodeToString(publicKey.serialize()) + "\n" +
                PUB_KEY_FOOTER).getBytes();
    }

    /**
     * Create ephemeral public key pair and log it
     * Calculate shared secret from server public key and ephemeral private key and log it
     * Encrypt client public key using shared secret and log it
     * Write encrypted client public key to file
     * Write ephemeral public key to file
     *
     * @param clientPublicKey
     * @param serverIdentityPublicKey
     * @return File-to-be bytes
     * @throws GeneralSecurityException
     * @throws InvalidKeyException
     */
    public static byte[] createEncryptedEncodedPublicKeyBytes(ECPublicKey clientPublicKey,
                                                              ECPublicKey serverIdentityPublicKey) throws
            GeneralSecurityException, InvalidKeyException {
        ECKeyPair ephemeralKeyPair = Curve.generateKeyPair();
        byte[] agreement = Curve.calculateAgreement(serverIdentityPublicKey, ephemeralKeyPair.getPrivateKey());
        String sharedSecret = Base64.getEncoder().encodeToString(agreement);
        String encryptedClientPubKey = encryptAesCbcPkcs5(sharedSecret,
                                                          Base64.getEncoder()
                                                                  .encodeToString(clientPublicKey.serialize()),
                                                          false);
        return (EC_ENCRYPTED_PUBLIC_KEY_HEADER + "\n" +
                Base64.getUrlEncoder().encodeToString(encryptedClientPubKey.getBytes()) + "\n" +
                Base64.getUrlEncoder().encodeToString(ephemeralKeyPair.getPublicKey().serialize()) + "\n" +
                EC_ENCRYPTED_PUBLIC_KEY_FOOTER).getBytes();
    }

    /**
     * Read encrypted client public key and ephemeral public key from file
     * Decode ephemeral public key and log it
     * Calculate shared secret from server private key and ephemeral public key and log it
     * Decrypt client public key using shared secret
     * Return decrypted client public key
     *
     * @param ServerPrivKey
     * @param clientEncFile
     * @return Decrypted public key
     * @throws IOException
     * @throws InvalidKeyException
     * @throws NoSuchAlgorithmException
     */
    public static String decodeEncryptedPublicKeyfromFile(ECPrivateKey ServerPrivKey, Path clientEncFile) throws
            IOException, InvalidKeyException, NoSuchAlgorithmException {
        List<String> encodedKeyList = Files.readAllLines(clientEncFile);
        if (encodedKeyList.size() != 4) {
            throw (new IOException(
                    "Wrong use of decode encrypted key... this key is probably not encrypted or is an old client... " +
                            "here is the key header: " + encodedKeyList.get(0)));
        }
        if ((encodedKeyList.get(0).equals(EC_ENCRYPTED_PUBLIC_KEY_HEADER)) &&
                (encodedKeyList.get(3).equals(EC_ENCRYPTED_PUBLIC_KEY_FOOTER))) {
            byte[] encryptedClientPublicKey = Base64.getUrlDecoder().decode(encodedKeyList.get(1));
            var ephemeralKeyBytes = Base64.getUrlDecoder().decode(encodedKeyList.get(2));
            ECPublicKey ephemeralPublicKey = Curve.decodePoint(ephemeralKeyBytes, 0);
            byte[] agreement = Curve.calculateAgreement(ephemeralPublicKey, ServerPrivKey);
            String sharedSecret = Base64.getEncoder().encodeToString(agreement);
            byte[] decryptedClientPubKey;
            try {
                decryptedClientPubKey = decryptAesCbcPkcs5(sharedSecret, new String(encryptedClientPublicKey));
            } catch (GeneralSecurityException e) {
                throw new RuntimeException("AES decryption failed: " + e.getMessage(), e);
            }
            String keyInStandardBase64Characters = new String(decryptedClientPubKey);
            keyInStandardBase64Characters = keyInStandardBase64Characters.replace('+', '-').replace('/', '_');
            return keyInStandardBase64Characters;
        } else {
            throw new InvalidKeyException(String.format("Error: %s has invalid public key header or footer",
                                                        clientEncFile.getFileName()));
        }
    }

    public static byte[] decodePublicKeyfromFile(Path path) throws IOException, InvalidKeyException {
        List<String> encodedKeyList = Files.readAllLines(path);

        if (encodedKeyList.size() != 3) {
            throw new InvalidKeyException(String.format("Error: %s should have three lines: HEADER, KEY, FOOTER",
                                                        path.getFileName()));
        }

        if ((encodedKeyList.get(0).equals(PUB_KEY_HEADER)) && (encodedKeyList.get(2).equals(PUB_KEY_FOOTER))) {
            return Base64.getUrlDecoder().decode(encodedKeyList.get(1));
        } else {
            throw new InvalidKeyException(String.format("Error: %s has invalid public key header or footer",
                                                        path.getFileName()));
        }
    }

    public static byte[] decodePrivateKeyFromFile(Path path) throws IOException, InvalidKeyException {
        List<String> encodedKeyList = Files.readAllLines(path);

        if (encodedKeyList.size() != 3) {
            throw new InvalidKeyException(String.format("Error: %s should have three lines: HEADER, KEY, FOOTER",
                                                        path.getFileName()));
        }

        if (encodedKeyList.get(0).equals(PVT_KEY_HEADER) && encodedKeyList.get(2).equals(PVT_KEY_FOOTER)) {
            return Base64.getUrlDecoder().decode(encodedKeyList.get(1));
        } else {
            throw new InvalidKeyException(String.format("Error: %s has invalid private key header or footer",
                                                        path.getFileName()));
        }
    }

}
