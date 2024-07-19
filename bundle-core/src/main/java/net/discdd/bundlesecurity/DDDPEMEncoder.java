package net.discdd.bundlesecurity;

import java.util.Base64;

/**
 * This class is a bit funky. It uses URL base64 encoding instead of the standard base64 encoding.
 */
public class DDDPEMEncoder {

    private static final String KEY_HEADER = "-----BEGIN %s KEY-----\n";
    private static final String KEY_FOOTER = "\n-----END %s KEY-----";

    public static final String ECPrivateKeyType = "EC PRIVATE";
    public static final String ECPublicKeyType = "EC PUBLIC";

    public static String encode(byte[] bytes, String type) {
        String base64EncodedPrivateKey = Base64.getUrlEncoder().encodeToString(bytes);
        return String.format(KEY_HEADER, type) + base64EncodedPrivateKey + String.format(KEY_FOOTER, type);
    }
}
