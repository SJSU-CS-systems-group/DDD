package net.discdd.server.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import net.discdd.server.bundlerouting.RoutingSource;

import java.io.Serializable;

/** Key of a {@link RoutingEdge}: one row per peer, client and source. */
@Embeddable
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public class RoutingEdgeId implements Serializable {
    /** longest peer or client ID a row holds; IDs are 28 characters today */
    public static final int MAX_ID_CHARS = 64;

    /** the carrier: {@code SecurityUtils.peerIdForTlsKey} of a transport's or dropbox's TLS key */
    @Column(name = "peer_id", length = MAX_ID_CHARS, nullable = false)
    public String peerId;

    /** the client the peer can reach */
    @Column(name = "client_id", length = MAX_ID_CHARS, nullable = false)
    public String clientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 16, nullable = false)
    public RoutingSource source;
}
