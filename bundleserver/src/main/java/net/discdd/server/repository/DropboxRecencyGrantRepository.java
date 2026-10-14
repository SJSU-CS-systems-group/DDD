package net.discdd.server.repository;

import net.discdd.server.repository.entity.DropboxRecencyGrant;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/** Clients that asked for their own recency blob; see {@link DropboxRecencyGrant}. */
@Repository
public interface DropboxRecencyGrantRepository extends CrudRepository<DropboxRecencyGrant, String> {}
