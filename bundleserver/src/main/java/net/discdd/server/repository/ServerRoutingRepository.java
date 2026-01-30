package net.discdd.server.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import net.discdd.server.repository.compositeId.ServerRoutingId;
import net.discdd.server.repository.entity.ServerRouting;

@Repository
public interface ServerRoutingRepository extends CrudRepository<ServerRouting, ServerRoutingId> {
    List<ServerRouting> findByServerRoutingIdTransportID(String transportID);

    Optional<ServerRouting> findByServerRoutingIdClientID(String clientID);

    ServerRouting findByServerRoutingIdClientIDAndServerRoutingIdTransportID(String clientID, String transportID);
}
