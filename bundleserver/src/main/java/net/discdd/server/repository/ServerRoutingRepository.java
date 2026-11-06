package net.discdd.server.repository;

import net.discdd.server.repository.compositeId.ServerRoutingId;
import net.discdd.server.repository.entity.ServerRouting;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * The legacy {@code serverroutingtable}, now read-only: routing lives in {@code routing_edge}, and
 * {@code LegacyRoutingImporter} reads this table once. It is kept untouched so the previous server can still run on it.
 */
@org.springframework.stereotype.Repository
public interface ServerRoutingRepository extends Repository<ServerRouting, ServerRoutingId> {
    List<ServerRouting> findAll();
}
