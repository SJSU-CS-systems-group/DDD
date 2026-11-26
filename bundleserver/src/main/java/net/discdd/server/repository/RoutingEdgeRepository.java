package net.discdd.server.repository;

import net.discdd.server.bundlerouting.RoutingSource;
import net.discdd.server.repository.entity.RoutingEdge;
import net.discdd.server.repository.entity.RoutingEdgeId;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * Routing edges; see {@link RoutingEdge}. The bulk statements bypass the persistence context, so a caller that loaded
 * an edge earlier in the same transaction must not rely on its in-memory {@code lastSeenMs} afterwards.
 */
@Repository
public interface RoutingEdgeRepository extends CrudRepository<RoutingEdge, RoutingEdgeId> {
    /** raises {@code lastSeenMs} to {@code t} if the row exists and is older; returns the rows changed (0 or 1) */
    @Transactional
    @Modifying
    @Query("update RoutingEdge e set e.lastSeenMs = :t where e.id.peerId = :peer and e.id.clientId = :client and " +
            "e.id.source = :source and e.lastSeenMs < :t")
    int touch(@Param("peer") String peerId,
              @Param("client") String clientId,
              @Param("source") RoutingSource source,
              @Param("t") long t);

    List<RoutingEdge> findByIdPeerIdAndIdSourceIn(String peerId, Collection<RoutingSource> sources);

    /** edges of one source from any of {@code peerIds}; for a carrier's one-hop (via) clients */
    List<RoutingEdge> findByIdPeerIdInAndIdSource(Collection<String> peerIds, RoutingSource source);
}
