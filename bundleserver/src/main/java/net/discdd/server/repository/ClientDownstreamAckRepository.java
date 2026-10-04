package net.discdd.server.repository;

import net.discdd.server.repository.entity.ClientDownstreamAck;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/** Per-client highest acknowledged downstream counter; see {@link ClientDownstreamAck}. */
@Repository
public interface ClientDownstreamAckRepository extends CrudRepository<ClientDownstreamAck, String> {}
