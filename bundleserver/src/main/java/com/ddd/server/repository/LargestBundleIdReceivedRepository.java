package com.ddd.server.repository;

import java.util.Optional;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import com.ddd.server.repository.entity.LargestBundleIdReceived;

@Repository
public interface LargestBundleIdReceivedRepository extends CrudRepository<LargestBundleIdReceived, String>
{
  public Optional<LargestBundleIdReceived> findByClientId(String clientId);

  // public void save(LargestBundleIdReceived record);
}
