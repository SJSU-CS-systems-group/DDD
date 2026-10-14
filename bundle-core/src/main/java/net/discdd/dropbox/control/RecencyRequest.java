package net.discdd.dropbox.control;

import java.util.Base64;
import java.util.Objects;

/**
 * Dropbox to server ({@code "type":"recencyRequest"}, under {@link DropboxControl#RECENCY_APP_ID}): asks the server to
 * mint a recency blob for the dropbox's own TLS key.
 *
 * <p>The request carries the X.509 DER of the dropbox's TLS EC public key and an ECDSA signature by that key over
 * {@link RecencyRequestSigner#signedBytes}, which binds it to the DDD client ID whose bundle carries the ADU. The
 * server answers a request that {@link RecencyRequestSigner#verify} accepts with a {@link RecencyGrant} for
 * {@link RecencyRequestSigner#senderIdFor} the key, which is the sender ID the gRPC {@code getRecencyBlob} path would
 * compute for a TLS peer presenting that key. The codec checks only the shape; the signature is checked by
 * {@link RecencyRequestSigner#verify}.
 *
 * <p>{@link #getRequestSeq()} is monotonic per dropbox endpoint; the dropbox keeps at most one request outstanding.
 */
public final class RecencyRequest extends DropboxControlMessage {
    private long requestSeq;
    /** Standard Base64 of the X.509 DER ({@code PublicKey.getEncoded()}) of the dropbox TLS EC public key. */
    private String tlsPublicKey;
    /** Standard Base64 of the DER-encoded {@code SHA256withECDSA} signature by that key. */
    private String signature;

    /** For Gson. */
    private RecencyRequest() {}

    /**
     * Usually built by {@link RecencyRequestSigner#sign}.
     *
     * @param requestSeq   monotonic per dropbox endpoint, starting at 1
     * @param tlsPublicKey standard Base64 of the X.509 DER of the dropbox TLS EC public key
     * @param signature    standard Base64 of the ECDSA signature over {@link RecencyRequestSigner#signedBytes}
     */
    public RecencyRequest(long requestSeq, String tlsPublicKey, String signature) {
        this.requestSeq = requestSeq;
        this.tlsPublicKey = tlsPublicKey;
        this.signature = signature;
    }

    @Override
    public String type() {
        return DropboxControl.TYPE_RECENCY_REQUEST;
    }

    public long getRequestSeq() {return requestSeq;}

    /** @return standard Base64 of the X.509 DER of the dropbox TLS public key */
    public String getTlsPublicKey() {return tlsPublicKey;}

    /** @return standard Base64 of the ECDSA signature */
    public String getSignature() {return signature;}

    /**
     * @return the decoded X.509 DER of the TLS public key (a fresh array)
     * @throws IllegalArgumentException if the field is not Base64 (never for a decoded or encoded message)
     */
    public byte[] getTlsPublicKeyDer() {return Base64.getDecoder().decode(tlsPublicKey);}

    @Override
    void validate() throws DropboxControlException {
        requirePositive(requestSeq, "requestSeq");
        requireBase64(tlsPublicKey, DropboxControl.MAX_TLS_PUBLIC_KEY_CHARS, "tlsPublicKey");
        requireBase64(signature, DropboxControl.MAX_SIGNATURE_CHARS, "signature");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RecencyRequest)) return false;
        RecencyRequest that = (RecencyRequest) o;
        return requestSeq == that.requestSeq && Objects.equals(tlsPublicKey, that.tlsPublicKey) &&
                Objects.equals(signature, that.signature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(requestSeq, tlsPublicKey, signature);
    }

    @Override
    public String toString() {
        return "RecencyRequest{requestSeq=" + requestSeq + ", tlsPublicKey=" +
                (tlsPublicKey == null ? 0 : tlsPublicKey.length()) + " chars}";
    }
}
