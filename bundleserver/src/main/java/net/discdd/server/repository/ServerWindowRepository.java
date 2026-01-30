package net.discdd.server.repository;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import net.discdd.server.repository.entity.ServerWindow;

@Repository
public interface ServerWindowRepository extends CrudRepository<ServerWindow, String> {
    ServerWindow findByClientID(String clientID);
}
