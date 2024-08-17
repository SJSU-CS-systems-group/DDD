package net.discdd.server.repository;

import net.discdd.server.repository.entity.ClientBundleCounters;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ClientBundleCountersRepository extends CrudRepository<ClientBundleCounters, String> {}
