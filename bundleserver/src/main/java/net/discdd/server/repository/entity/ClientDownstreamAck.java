package net.discdd.server.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * The highest downstream (server to client) bundle counter a client has acknowledged.
 * <p>
 * A client's ACK is the exact encrypted ID of the newest downstream bundle it accepted, and it rejects anything
 * older afterwards, so every downstream bundle with a counter at or below {@link #ackedCounter} is delivered or
 * obsolete for that client (Research R8 §4). The counter comes from the never-deleted {@code BundleMetadata} row of
 * the acked ID, so no decryption is needed. {@code ServerWindow.startCounter} is not used for this because its
 * default of 1 cannot distinguish "nothing acked" from "acked bundle 1".
 */
@Entity
@Table(name = "client_downstream_ack")
@NoArgsConstructor
@AllArgsConstructor
public class ClientDownstreamAck {
    @Id
    @Column(length = 255)
    public String clientId;

    /** highest acked downstream counter; only ever increases */
    public long ackedCounter;

    /** the encrypted downstream bundle ID that carried {@link #ackedCounter} */
    @Column(length = 255)
    public String ackedBundleId;

    /** when {@link #ackedCounter} last increased, in epoch milliseconds */
    public long updatedAtMs;
}
