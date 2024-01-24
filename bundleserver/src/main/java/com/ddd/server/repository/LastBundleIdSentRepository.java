package com.ddd.server.repository;

import java.util.Optional;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import com.ddd.server.repository.entity.LastBundleIdSent;

@Repository
public interface LastBundleIdSentRepository extends CrudRepository<LastBundleIdSent, String>
{
  public Optional<LastBundleIdSent> findByClientId(String clientId);

  // public void save(LastBundleIdSent record);
}
