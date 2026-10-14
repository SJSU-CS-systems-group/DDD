package net.discdd.server.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;
import net.discdd.dropbox.control.DropboxControl;

/**
 * A client that asked for its own recency blob with a {@code RecencyRequest} control ADU
 * ({@link DropboxControl#RECENCY_APP_ID}), and the key the newest grant was minted for ("09 Own Recency Blob Design"
 * § Server). A client gets {@link DropboxControl#RECENCY_APP_ID} in its {@code app_ids.txt} while it has a row here or
 * while a grant is still queued for it in the send store (see {@code DropboxRecencyHandler#isRegisteredFor}); stock
 * clients are unaffected.
 * <p>
 * Any client may ask, as any TLS peer may call the gRPC {@code getRecencyBlob}; the request signature proves it holds
 * the TLS key the blob is minted for. The row is bookkeeping: which queued grant is current is read from the send
 * store, not from here (see {@code DropboxRecencyHandler}).
 */
@Entity
@Table(name = "dropbox_recency_grant")
@NoArgsConstructor
public class DropboxRecencyGrant {
    /** the requester's own client ID (the dropbox's embedded control endpoint) */
    @Id
    @Column(length = 255)
    public String clientId;

    /** the newest granted request's {@code tlsPublicKey}: standard Base64 of the X.509 DER */
    @Column(length = DropboxControl.MAX_TLS_PUBLIC_KEY_CHARS)
    public String tlsPublicKey;

    /** the {@code senderId} the newest grant's blob names, derived from {@link #tlsPublicKey} */
    @Column(length = 255)
    public String senderId;

    /** {@code requestSeq} of the newest granted request */
    public long lastRequestSeq;

    /** when the newest grant was minted (its blob timestamp) */
    public long lastGrantMs;

    public long firstSeenMs;

    public DropboxRecencyGrant(String clientId, long nowMs) {
        this.clientId = clientId;
        this.firstSeenMs = nowMs;
    }
}
