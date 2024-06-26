package com.ddd.server.repository;

import com.ddd.server.repository.entity.ServerWindow;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ServerWindowRepository extends CrudRepository<ServerWindow, String> {
    Optional<ServerWindow> findClientID(String clientID);
}
