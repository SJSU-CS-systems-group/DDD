package com.ddd.server.repository;

import java.util.Optional;
import com.ddd.server.repository.entity.SentBundleDetails;

public interface SentBundleDetailsRepository
// extends CrudRepository<SentBundleDetails, String>
{
  //  public Optional<SentBundleDetails> findByBundleId(String bundleId);
  //
  //  public void save(SentBundleDetails record);
  //
  //  public void deleteByBundleId(String bundleId);

  public Optional<SentBundleDetails> findByBundleId(String bundleId);

  public void save(SentBundleDetails sentBundleDetails);
}
