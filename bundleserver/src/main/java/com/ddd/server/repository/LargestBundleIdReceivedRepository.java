package com.ddd.server.repository;

import java.util.Optional;
import com.ddd.server.repository.entity.LargestBundleIdReceived;

public interface LargestBundleIdReceivedRepository
//    extends CrudRepository<LargestBundleIdReceived, String>
{
  public Optional<LargestBundleIdReceived> findByClientId(String clientId);

  public void save(LargestBundleIdReceived record);
}
