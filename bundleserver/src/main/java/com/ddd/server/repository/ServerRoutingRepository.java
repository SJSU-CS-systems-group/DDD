package com.ddd.server.repository;

import com.ddd.server.repository.entity.ServerRouting;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ServerRoutingRepository extends CrudRepository<ServerRouting, ServerRoutingId> {
    List<ServerRouting> findByServerRoutingIdTransportID(String transportID);

    Optional<ServerRouting> findByServerRoutingIdClientID(String clientID);
}
