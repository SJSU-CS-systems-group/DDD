package com.ddd.server.repository;

import com.ddd.server.repository.entity.RegisteredAppAdapter;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RegisteredAppAdapterRepository extends CrudRepository<RegisteredAppAdapter, String> {
    Optional<RegisteredAppAdapter> findByAppId(String appId);
}
