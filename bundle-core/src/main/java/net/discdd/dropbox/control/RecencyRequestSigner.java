package net.discdd.dropbox.control;

import net.discdd.bundlesecurity.SecurityUtils;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Signs and verifies {@link RecencyRequest}s, and derives the sender ID the server mints the granted blob for.
 *
 * <p>The signature proves the dropbox holds the private half of the TLS key it asks a blob for, so no one gets a
 * blob for a key they do not hold. It covers the UTF-8 bytes of
 *
 * <pre>"net.discdd.dropbox.recency-request.v1\n" + clientId + "\n" + requestSeq + "\n" + tlsPublicKey</pre>
 *
 * <p>where {@code clientId} is the DDD client ID of the endpoint whose bundle carries the request (the server knows it
 * from the bundle), so a request cannot be replayed from another endpoint. The algorithm is {@code SHA256withECDSA}
 * over the dropbox's TLS EC key ({@code DDDTLSUtil.generateKeyPair}, P-256).
 *
 * <p>Stateless. Pure Java; must stay within the Android API 33 surface that animal-sniffer enforces for bundle-core.
 */
public final class RecencyRequestSigner {
    /** Domain-separation prefix of {@link #signedBytes}, versioned so a future format cannot be confused with it. */
    public static final String SIGNED_PREFIX = "net.discdd.dropbox.recency-request.v1\n";

    /** JCA signature algorithm of the request signature. */
    public static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";

    /** JCA key algorithm of the TLS key. */
    public static final String KEY_ALGORITHM = "EC";

    private RecencyRequestSigner() {}

    /**
     * @param clientId     DDD client ID of the endpoint whose bundle carries the request
     * @param requestSeq   the request sequence, > 0
     * @param tlsPublicKey the request's {@code tlsPublicKey} field, verbatim
     * @return the bytes the request signature covers
     * @throws IllegalArgumentException if {@code clientId} or {@code tlsPublicKey} is null, empty or contains a
     *                                  newline, or {@code requestSeq <= 0}
     */
    public static byte[] signedBytes(String clientId, long requestSeq, String tlsPublicKey) {
        requireField(clientId, "clientId");
        requireField(tlsPublicKey, "tlsPublicKey");
        if (requestSeq <= 0) throw new IllegalArgumentException("requestSeq must be > 0, was " + requestSeq);
        return (SIGNED_PREFIX + clientId + "\n" + requestSeq + "\n" + tlsPublicKey).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Builds a signed request for the dropbox's TLS key.
     *
     * @param clientId   DDD client ID of the dropbox endpoint that will carry the request
     * @param requestSeq the request sequence, > 0
     * @param tlsKeyPair the dropbox TLS EC key pair (the one its gRPC certificate presents)
     * @return a request that passes {@link #verify} for {@code clientId}
     * @throws GeneralSecurityException if the key pair cannot sign with {@link #SIGNATURE_ALGORITHM}
     * @throws IllegalArgumentException as {@link #signedBytes}, or if {@code tlsKeyPair} is null
     */
    public static RecencyRequest sign(String clientId, long requestSeq, KeyPair tlsKeyPair) throws
            GeneralSecurityException {
        if (tlsKeyPair == null || tlsKeyPair.getPublic() == null || tlsKeyPair.getPrivate() == null) {
            throw new IllegalArgumentException("tlsKeyPair is missing a key");
        }
        String tlsPublicKey = Base64.getEncoder().encodeToString(tlsKeyPair.getPublic().getEncoded());
        Signature signer = Signature.getInstance(SIGNATURE_ALGORITHM);
        signer.initSign(tlsKeyPair.getPrivate());
        signer.update(signedBytes(clientId, requestSeq, tlsPublicKey));
        return new RecencyRequest(requestSeq, tlsPublicKey, Base64.getEncoder().encodeToString(signer.sign()));
    }

    /**
     * Checks that {@code request} is well formed and signed by the private half of its own {@code tlsPublicKey}
     * for {@code clientId}. Never throws: any malformed input, key or signature yields false.
     *
     * @param clientId DDD client ID of the endpoint whose bundle carried the request
     * @param request  the decoded request
     * @return true only if the signature verifies
     */
    public static boolean verify(String clientId, RecencyRequest request) {
        try {
            if (clientId == null || request == null) return false;
            request.validate();
            PublicKey key = KeyFactory.getInstance(KEY_ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(request.getTlsPublicKeyDer()));
            Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
            verifier.initVerify(key);
            verifier.update(signedBytes(clientId, request.getRequestSeq(), request.getTlsPublicKey()));
            return verifier.verify(Base64.getDecoder().decode(request.getSignature()));
        } catch (DropboxControlException | GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The sender ID a blob for this key is minted for: {@code SecurityUtils.generateID} of the X.509 DER, which is the
     * formula the server's gRPC {@code getRecencyBlob} applies to a TLS peer certificate's
     * {@code getPublicKey().getEncoded()}. The dropbox presents that same certificate to clients.
     *
     * @param tlsPublicKey standard Base64 of the X.509 DER (a request's {@code tlsPublicKey} field)
     * @return the sender ID
     * @throws IllegalArgumentException if {@code tlsPublicKey} is null or not standard Base64
     */
    public static String senderIdFor(String tlsPublicKey) {
        if (tlsPublicKey == null) throw new IllegalArgumentException("tlsPublicKey is null");
        return SecurityUtils.generateID(Base64.getDecoder().decode(tlsPublicKey));
    }

    private static void requireField(String value, String field) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException(field + " is missing");
        if (value.indexOf('\n') >= 0) throw new IllegalArgumentException(field + " contains a newline");
    }
}
