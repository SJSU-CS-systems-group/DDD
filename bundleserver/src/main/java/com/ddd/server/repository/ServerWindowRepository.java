package com.ddd.server.repository;

import com.ddd.server.repository.entity.ServerWindow;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ServerWindowRepository extends CrudRepository<ServerWindow, String> {
    ServerWindow findByClientID(String clientID);
}
