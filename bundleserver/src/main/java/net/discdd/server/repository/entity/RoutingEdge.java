package net.discdd.server.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

/**
 * "Peer {@code peerId} could reach client {@code clientId}, last seen at {@code lastSeenMs}", learned from
 * {@code source}. It replaces the legacy {@code serverroutingtable}, whose primary key {@code ddl-auto: update} cannot
 * change. Rows are only upserted ({@code BundleRouting}); every read ignores rows older than {@code routing.ttl}, and
 * {@code RoutingSweeper} deletes rows older than twice that.
 */
@Entity
@Table(name = "routing_edge", indexes = { @Index(name = "idx_routing_edge_last_seen", columnList = "last_seen_ms") })
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
