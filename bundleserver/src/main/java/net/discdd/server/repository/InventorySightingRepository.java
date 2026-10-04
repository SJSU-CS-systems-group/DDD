package net.discdd.server.repository;

import net.discdd.server.repository.compositeId.InventorySightingId;
import net.discdd.server.repository.entity.InventorySighting;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** First-sighting times of bundle IDs in dropbox inventories; see {@link InventorySighting}. */
@Repository
public interface InventorySightingRepository extends CrudRepository<InventorySighting, InventorySightingId> {
    List<InventorySighting> findByIdDropboxId(String dropboxId);
}
