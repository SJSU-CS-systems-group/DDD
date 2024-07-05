package net.discdd.server.repository;

import java.util.Optional;

import net.discdd.server.repository.entity.LastBundleIdSent;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LastBundleIdSentRepository extends CrudRepository<LastBundleIdSent, String> {
    Optional<LastBundleIdSent> findByClientId(String clientId);

    // public void save(LastBundleIdSent record);
}
