package net.discdd.server.dropbox;

import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.bundlesecurity.ServerSecurity;
import org.whispersystems.libsignal.ecc.Curve;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;

/**
 * Per-client AES keys for decrypting upstream bundle IDs, derived once and kept in a bounded LRU cache. It
 * reproduces bundle-core exactly: {@code ServerSecurity.decryptBundleID} is
 * {@code SecurityUtils.decryptAesCbcPkcs5(secret, id, true)} with
 * {@code secret = Base64url(agreement(clientIdentity, serverIdentityPrivate))}, a PBKDF2WithHmacSHA256 key
 * ({@link SecurityUtils#ITERATIONS} rounds, the secret's bytes as salt) and AES-CBC with a zero IV. bundle-core
 * redoes the 65,536-round derivation on every call; caching it is what keeps planning cheap (review v2). The
 * dropbox does the same on its side ({@code DropboxControlEndpoint.deriveBundleIdKey}).
 * <p>
 * A client ID is the hash of its identity key, so a cached key never goes stale for that ID; the server's identity
 * only changes with a restart, which empties this cache. Thread-safe.
 */
final class BundleIdKeys {
    private static final Logger logger = Logger.getLogger(BundleIdKeys.class.getName());
    private static final int MAX_CLIENTS = 4096;

    private final Map<String, Optional<SecretKeySpec>> keys = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Optional<SecretKeySpec>> eldest) {
            return size() > MAX_CLIENTS;
        }
    };

    /** @return the key for {@code clientId}, or null if the server has no keys for that client */
    synchronized SecretKeySpec keyFor(ServerSecurity serverSecurity, String clientId) {
        return keys.computeIfAbsent(clientId, c -> derive(serverSecurity, c)).orElse(null);
    }

    private static Optional<SecretKeySpec> derive(ServerSecurity serverSecurity, String clientId) {
        try {
            byte[] agreement = Curve.calculateAgreement(serverSecurity.getClientIdentityPublicKey(clientId),
                                                        serverSecurity.getSigningKey());
            String secret = Base64.getUrlEncoder().encodeToString(agreement);
            var spec = new PBEKeySpec(secret.toCharArray(), secret.getBytes(StandardCharsets.UTF_8),
                                      SecurityUtils.ITERATIONS, SecurityUtils.KEYLEN);
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Optional.of(new SecretKeySpec(key, "AES"));
        } catch (Exception e) {
            // an unknown client (no stored session or keys) is expected for foreign or stale entries
            logger.log(FINE, "No bundle ID key for client " + clientId, e);
            return Optional.empty();
        }
    }

    /** decrypts a Base64url bundle ID; throws if the padding does not check out (wrong key) */
    static String decrypt(SecretKeySpec key, String encryptedBundleId) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(new byte[16]));
        byte[] plain = cipher.doFinal(Base64.getUrlDecoder().decode(encryptedBundleId));
        return new String(plain, StandardCharsets.UTF_8);
    }
}
