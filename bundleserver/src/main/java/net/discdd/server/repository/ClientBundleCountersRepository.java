package net.discdd.server.repository;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import net.discdd.server.repository.entity.ClientBundleCounters;

@Repository
public interface ClientBundleCountersRepository extends CrudRepository<ClientBundleCounters, String> {}
