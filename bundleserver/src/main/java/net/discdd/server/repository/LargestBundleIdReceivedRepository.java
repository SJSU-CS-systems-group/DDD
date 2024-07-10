package net.discdd.server.repository;

import java.util.Optional;

import net.discdd.server.repository.entity.LargestBundleIdReceived;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LargestBundleIdReceivedRepository extends CrudRepository<LargestBundleIdReceived, String> {
    Optional<LargestBundleIdReceived> findByClientId(String clientId);

    // public void save(LargestBundleIdReceived record);
}
