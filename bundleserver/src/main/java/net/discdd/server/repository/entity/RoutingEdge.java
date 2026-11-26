package net.discdd.server.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

/**
 * "Peer {@code peerId} could reach client {@code clientId}, last seen at {@code lastSeenMs}", learned from
 * {@code source}. It replaces the legacy {@code serverroutingtable}, whose primary key {@code ddl-auto: update} cannot
 * change. Rows are only upserted ({@code BundleRouting}) and never expire.
 */
@Entity
@Table(name = "routing_edge")
@NoArgsConstructor
public class RoutingEdge {
    @EmbeddedId
    public RoutingEdgeId id;

    /** epoch milliseconds of the newest evidence */
    @Column(name = "last_seen_ms", nullable = false)
    public long lastSeenMs;

    public RoutingEdge(RoutingEdgeId id, long lastSeenMs) {
        this.id = id;
        this.lastSeenMs = lastSeenMs;
    }
}
