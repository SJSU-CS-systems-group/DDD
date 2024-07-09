package net.discdd.server.repository;

import net.discdd.server.repository.entity.RegisteredAppAdapter;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RegisteredAppAdapterRepository extends CrudRepository<RegisteredAppAdapter, String> {
    Optional<RegisteredAppAdapter> findByAppId(String appId);
    @Query("SELECT appId FROM RegisteredAppAdapter")
    Collection<String> findAllAppIds();
}
