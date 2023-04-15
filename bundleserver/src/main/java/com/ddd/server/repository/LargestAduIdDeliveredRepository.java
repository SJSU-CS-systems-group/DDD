package com.ddd.server.repository;

import java.util.Optional;
import com.ddd.server.repository.entity.LargestAduIdDelivered;

public interface LargestAduIdDeliveredRepository
//    extends CrudRepository<LargestAduIdDelivered, String>
{

  //  @Query(
  //      "select client_id, app_id, adu_id from largest_adu_id_delivered where client_id =
  // :clientId,  app_id = :appId")
  //  public Optional<LargestAduIdDelivered> findByClientIdAndAppId(
  //      @Param("clientId") String clientId, @Param("appId") String appId);

  public Optional<LargestAduIdDelivered> findByClientIdAndAppId(String clientId, String appId);

  public void save(LargestAduIdDelivered record);
}
