package net.discdd.server.repository;

import net.discdd.server.repository.entity.ServerRouting;
import net.discdd.server.repository.compositeId.ServerRoutingId;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ServerRoutingRepository extends CrudRepository<ServerRouting, ServerRoutingId> {
    List<ServerRouting> findByServerRoutingIdTransportID(String transportID);

    List<ServerRouting> findByServerRoutingIdClientID(String clientID);

    ServerRouting findByServerRoutingIdClientIDAndServerRoutingIdTransportID(String clientID, String transportID);
}
