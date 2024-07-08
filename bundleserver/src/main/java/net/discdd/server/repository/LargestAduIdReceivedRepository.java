package net.discdd.server.repository;

import java.util.Optional;

import net.discdd.server.repository.entity.LargestAduIdReceived;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LargestAduIdReceivedRepository extends CrudRepository<LargestAduIdReceived, String> {

    //  @Query("select client_id, app_id, adu_id from largest_adu_id_received")
    //  public Optional<LargestAduIdReceived> findByClientIdAndAppId(String clientId, String appId);

    Optional<LargestAduIdReceived> findByClientIdAndAppId(String clientId, String appId);

    // public void save(LargestAduIdReceived record);
}
