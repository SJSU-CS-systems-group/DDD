package com.ddd.server.repository;

import com.ddd.server.repository.entity.ClientDataChanged;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface ClientDataChangedRepository extends CrudRepository<ClientDataChanged, String> {
    Optional<String> findByClientId(String clientId);
}
