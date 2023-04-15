package com.ddd.server.repository;

import java.util.List;
import java.util.Optional;
import com.ddd.server.repository.entity.SentAduDetails;

public interface SentAduDetailsRepository
// extends CrudRepository<SentAduDetails, String>
{
  public Optional<SentAduDetails> findById(String id);

  public List<SentAduDetails> findByBundleId(String bundleId);

  public void save(SentAduDetails record);

  public void deleteByBundleId(String bundleId);
}
