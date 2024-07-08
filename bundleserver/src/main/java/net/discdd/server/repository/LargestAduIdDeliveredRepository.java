package net.discdd.server.repository;

import net.discdd.server.repository.entity.LargestAduIdDelivered;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LargestAduIdDeliveredRepository extends CrudRepository<LargestAduIdDelivered, String> {

    Optional<LargestAduIdDelivered> findByClientIdAndAppId(String clientId, String appId);
}
