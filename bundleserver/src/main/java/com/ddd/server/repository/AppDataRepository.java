package com.ddd.server.repository;

import com.ddd.server.repository.entity.AppData;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface AppDataRepository extends CrudRepository<AppData, String> {
    Optional<String> findByAppName(String appName);
}
