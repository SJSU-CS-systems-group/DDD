package net.discdd.server.repository;

import net.discdd.server.repository.entity.DropboxNode;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/** Clients known to be DDD Dropboxes; see {@link DropboxNode}. */
@Repository
public interface DropboxNodeRepository extends CrudRepository<DropboxNode, String> {}
