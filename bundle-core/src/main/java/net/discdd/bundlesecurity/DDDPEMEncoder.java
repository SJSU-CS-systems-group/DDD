package net.discdd.bundlesecurity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;

/**
 * This class is a bit funky. It uses URL base64 encoding instead of the standard base64 encoding.
 */
public class DDDPEMEncoder {
    private static final Logger logger = Logger.getLogger(DDDPEMEncoder.class.getName());

    private static final String KEY_HEADER = "-----BEGIN %s-----";
    private static final String KEY_FOOTER = "-----END %s-----";

    public static final String ECPrivateKeyType = "EC PRIVATE KEY";
    public static final String ECPublicKeyType = "EC PUBLIC KEY";
    public static final String privateKeyType = "PRIVATE KEY";
    public static final String publicKeyType = "PUBLIC KEY";
    public static final String CERTIFICATE = "CERTIFICATE";

//    public static String encode(byte[] bytes, String type) {
//        String base64EncodedPrivateKey = Base64.getUrlEncoder().encodeToString(bytes);
//        return String.format(KEY_HEADER, type) + "\n" + base64EncodedPrivateKey + "\n" + String.format(KEY_FOOTER, type);
//    }

    public static String encode(byte[] bytes, String type) {
        if (type.equals(CERTIFICATE)) {
            return String.format(KEY_HEADER, type) + "\n" + Base64.getEncoder().encodeToString(bytes) + "\n" + String.format(KEY_FOOTER, type);
        }

        return String.format(KEY_HEADER, type) + "\n" + Base64.getUrlEncoder().encodeToString(bytes) + "\n" + String.format(KEY_FOOTER, type);
    }

    public static byte[] decodeFromFile(Path path, String type) throws IOException {
        List<String> encodedKeyList = Files.readAllLines(path);

        if (encodedKeyList.size() != 3) {
            logger.log(SEVERE, String.format("Error: %s should have three lines: HEADER, KEY, FOOTER", path.getFileName()));
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
}
