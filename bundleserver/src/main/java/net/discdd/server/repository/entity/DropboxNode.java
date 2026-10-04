package net.discdd.server.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

/**
 * A client ID that has identified itself as a DDD Dropbox by sending an inventory control ADU.
 * <p>
 * Registration is trust-on-first-use (Research R6 §3.3): the only effect of being a dropbox is that the server sends
 * this client retention plans about copies held <em>on that dropbox</em>, so a forged inventory cannot delete
 * anything on the server. Only clients with a row here get {@code DropboxControl.APP_ID} in their {@code app_ids.txt};
 * stock clients are unaffected.
 */
@Entity
@Table(name = "dropbox_node")
@NoArgsConstructor
public class DropboxNode {
    /** the dropbox's own client ID (its embedded control endpoint) */
    @Id
    @Column(length = 255)
    public String clientId;

    /** {@code reportSeq} of the newest inventory accepted; older or equal reports are ignored */
    public long lastReportSeq;

    /** {@code lastAppliedPlanSeq} the dropbox claimed in that report */
    public long lastAppliedPlanSeq;

    /** {@code planSeq} of the newest plan queued for this dropbox; plans are numbered from 1 */
    public long lastPlanSeq;

    public long capacityBytes;
    public long usedBytes;

    /** the newest accepted inventory, as received (UTF-8 JSON), re-planned before each bundle for this dropbox */
    @Lob
    @Column(length = 8 * 1024 * 1024)
    public String lastInventoryJson;

    /** the newest plan queued for this dropbox (UTF-8 JSON); null forces the next re-plan to enqueue a plan */
    @Lob
    @Column(length = 8 * 1024 * 1024)
    public String lastPlanJson;

    public long firstSeenMs;
    /** when the newest inventory was accepted */
    public long lastReportMs;
    /** when the newest plan was queued */
    public long lastPlanMs;

    public DropboxNode(String clientId, long nowMs) {
        this.clientId = clientId;
        this.firstSeenMs = nowMs;
    }
}
