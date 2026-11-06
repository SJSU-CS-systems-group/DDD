package net.discdd.server.repository;

import net.discdd.server.repository.entity.DropboxNode;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** Clients known to be DDD Dropboxes; see {@link DropboxNode}. */
@Repository
public interface DropboxNodeRepository extends CrudRepository<DropboxNode, String> {
    /** which of {@code clientIds} are dropboxes, without loading their (large) inventory and plan columns */
    @Query("select n.clientId from DropboxNode n where n.clientId in :ids")
    List<String> findExistingClientIds(@Param("ids") Collection<String> clientIds);
}
