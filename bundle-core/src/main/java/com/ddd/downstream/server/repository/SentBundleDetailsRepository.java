package com.ddd.server.repository;

import java.util.Optional;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import com.ddd.server.repository.entity.SentBundleDetails;

@Repository
public interface SentBundleDetailsRepository extends CrudRepository<SentBundleDetails, String>
{
  //  public Optional<SentBundleDetails> findByBundleId(String bundleId);
  //
  //  public void save(SentBundleDetails record);
  //
  //  public void deleteByBundleId(String bundleId);

  Optional<SentBundleDetails> findByBundleId(String bundleId);

  // public void save(SentBundleDetails sentBundleDetails);
}
