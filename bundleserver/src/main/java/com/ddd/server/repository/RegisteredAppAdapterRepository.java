package com.ddd.server.repository;

import com.ddd.server.repository.entity.RegisteredAppAdapter;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RegisteredAppAdapterRepository extends CrudRepository<RegisteredAppAdapter, String> {
    //    @Query("SELECT raa.address FROM RegisteredAppAdapter raa WHERE raa.app_id=:appId")
    Optional<String> findAddressByAppId(String appId);
}
