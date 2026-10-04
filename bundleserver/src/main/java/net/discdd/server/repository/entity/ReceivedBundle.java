package net.discdd.server.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * One row per upstream (client to server) bundle the server has received, keyed by the encrypted bundle ID read
 * from the jar's plaintext {@code bundle.id} entry. That string is also the bundle's file name on transports and
 * dropboxes, so a dropbox inventory can be matched against this table without any decryption.
 * <p>
 * Rows are written by {@code ServerBundleTransmission.processReceivedBundle} <em>before</em> its duplicate check,
 * so duplicates and ack-only bundles are recorded too. Ack-only bundles never advance
 * {@code ClientBundleCounters.lastReceivedBundleCounter} (Research R8 §1), which is why the dropbox retention planner
 * uses this table, not the counters, as "the highest upstream counter received".
 */
@Entity
@Table(name = "received_bundle",
       indexes = { @Index(name = "idx_received_bundle_client", columnList = "clientId"),
                   @Index(name = "idx_received_bundle_key", columnList = "clientKeyFingerprint") })
@NoArgsConstructor
@AllArgsConstructor
public class ReceivedBundle {
    /** the encrypted bundle ID exactly as written in the jar's {@code bundle.id} entry */
    @Id
    @Column(length = 255)
    public String encryptedBundleId;

    /** the client that created the bundle */
    @Column(length = 255)
    public String clientId;

    /** the upstream counter decrypted from the bundle ID */
    public long bundleCounter;

    /**
     * {@code BundleJarInfo.clientKeyFingerprint} of the jar's plaintext {@code clientBase.pub}, or null if the entry
     * was missing or malformed. This is the {@code clientKeyB64} a dropbox reports, so it maps an upstream bundle the
     * server never received to a known client.
     */
    @Column(length = 64)
    public String clientKeyFingerprint;

    /** when the server first received this bundle, in epoch milliseconds */
    public long receivedAtMs;

    /** the TLS name of the uploader (a transport, or the client itself), never the dropbox that relayed it */
    @Column(length = 255)
    public String uploaderId;
}
