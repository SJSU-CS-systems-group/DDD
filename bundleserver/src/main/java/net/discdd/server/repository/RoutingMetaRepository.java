package net.discdd.server.repository;

import net.discdd.server.repository.entity.RoutingMeta;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/** Routing markers; see {@link RoutingMeta}. */
@Repository
public interface RoutingMetaRepository extends CrudRepository<RoutingMeta, String> {}
