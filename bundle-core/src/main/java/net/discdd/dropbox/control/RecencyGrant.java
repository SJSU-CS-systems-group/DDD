package net.discdd.dropbox.control;

import java.util.Base64;
import java.util.Objects;

/**
 * Server to dropbox ({@code "type":"recencyGrant"}, under {@link DropboxControl#RECENCY_APP_ID}): a recency blob the
 * server minted for the dropbox's own TLS key, in answer to a {@link RecencyRequest}.
 *
 * <p>{@link #getRecencyBlob()} is the Base64 of a serialized {@code GetRecencyBlobResponse}, exactly what the gRPC
 * {@code getRecencyBlob} call returns, with {@code senderId} = {@link RecencyRequestSigner#senderIdFor} the request's
 * key. The codec checks only the shape: the dropbox parses the blob and checks the server signature and the
 * {@code senderId} itself before it stores or serves the blob.
 *
 * <p>{@link #getRequestSeq()} echoes the answered request; a grant answers every outstanding request whose sequence
 * is not higher.
 */
public final class RecencyGrant extends DropboxControlMessage {
    private long requestSeq;
    /** Standard Base64 of a serialized {@code GetRecencyBlobResponse}. */
    private String recencyBlob;
    /** Epoch milliseconds when the server minted the blob; optional, informational only. */
    private Long issuedAt;

    /** For Gson. */
    private RecencyGrant() {}

    /**
     * @param requestSeq  the answered {@link RecencyRequest#getRequestSeq()}
     * @param recencyBlob standard Base64 of a serialized {@code GetRecencyBlobResponse}
     * @param issuedAt    epoch ms when the blob was minted, or null
     */
    public RecencyGrant(long requestSeq, String recencyBlob, Long issuedAt) {
        this.requestSeq = requestSeq;
        this.recencyBlob = recencyBlob;
        this.issuedAt = issuedAt;
    }

    /**
     * @param requestSeq       the answered {@link RecencyRequest#getRequestSeq()}
     * @param recencyBlobBytes {@code GetRecencyBlobResponse.toByteArray()}
     * @param issuedAt         epoch ms when the blob was minted, or null
     * @return a grant carrying the Base64 of {@code recencyBlobBytes}
     */
    public static RecencyGrant of(long requestSeq, byte[] recencyBlobBytes, Long issuedAt) {
        return new RecencyGrant(requestSeq, Base64.getEncoder().encodeToString(recencyBlobBytes), issuedAt);
    }

    @Override
    public String type() {
        return DropboxControl.TYPE_RECENCY_GRANT;
    }

    public long getRequestSeq() {return requestSeq;}

    /** @return standard Base64 of a serialized {@code GetRecencyBlobResponse} */
    public String getRecencyBlob() {return recencyBlob;}

    /**
     * @return the decoded {@code GetRecencyBlobResponse} bytes (a fresh array), for
     *         {@code GetRecencyBlobResponse.parseFrom}
     * @throws IllegalArgumentException if the field is not Base64 (never for a decoded or encoded message)
     */
    public byte[] getRecencyBlobBytes() {return Base64.getDecoder().decode(recencyBlob);}

    /** @return epoch ms when the blob was minted, or null if the server did not say */
    public Long getIssuedAt() {return issuedAt;}

    @Override
    void validate() throws DropboxControlException {
        requirePositive(requestSeq, "requestSeq");
        requireBase64(recencyBlob, DropboxControl.MAX_RECENCY_BLOB_CHARS, "recencyBlob");
        if (issuedAt != null) requireNonNegative(issuedAt, "issuedAt");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RecencyGrant)) return false;
        RecencyGrant that = (RecencyGrant) o;
        return requestSeq == that.requestSeq && Objects.equals(recencyBlob, that.recencyBlob) &&
                Objects.equals(issuedAt, that.issuedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(requestSeq, recencyBlob, issuedAt);
    }

    @Override
    public String toString() {
        return "RecencyGrant{requestSeq=" + requestSeq + ", recencyBlob=" +
                (recencyBlob == null ? 0 : recencyBlob.length()) + " chars, issuedAt=" + issuedAt + "}";
    }
}
