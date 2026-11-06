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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    /**
     * A plain VARCHAR, not the native {@code enum(...)} Hibernate 6 emits for {@code @Enumerated(STRING)} on MySQL:
     * {@code ddl-auto: update} never alters an existing column, so a native enum would reject every
     * {@link RoutingSource} value added later. A database that already created the table as an enum needs
     * {@code ALTER TABLE routing_edge MODIFY source VARCHAR(16) NOT NULL} once.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source", length = 16, nullable = false)
    public RoutingSource source;
}
