package com.ddd.server.repository;

import java.util.Optional;
import com.ddd.server.repository.entity.LastBundleIdSent;

public interface LastBundleIdSentRepository
// extends CrudRepository<LastBundleIdSent, String>
{
  public Optional<LastBundleIdSent> findByClientId(String clientId);

  public void save(LastBundleIdSent record);
}
