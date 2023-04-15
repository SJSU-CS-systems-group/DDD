package com.ddd.server.repository;

import java.util.Optional;
import com.ddd.server.repository.entity.LargestAduIdReceived;

public interface LargestAduIdReceivedRepository
//    extends CrudRepository<LargestAduIdReceived, String>
{

  //  @Query("select client_id, app_id, adu_id from largest_adu_id_received")
  //  public Optional<LargestAduIdReceived> findByClientIdAndAppId(String clientId, String appId);

  public Optional<LargestAduIdReceived> findByClientIdAndAppId(String clientId, String appId);

  public void save(LargestAduIdReceived record);
}
