package net.discdd.server.repository;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import net.discdd.server.repository.entity.RegisteredAppAdapter;

@Repository
public interface RegisteredAppAdapterRepository extends CrudRepository<RegisteredAppAdapter, String> {
    Optional<RegisteredAppAdapter> findByAppId(String appId);

    @Query("SELECT appId FROM RegisteredAppAdapter")
    Collection<String> findAllAppIds();
}
