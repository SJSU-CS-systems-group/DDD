package net.discdd.server.repository.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import net.discdd.server.repository.compositeId.InventorySightingId;

/**
 * When the server first saw a bundle ID in a dropbox's inventory. It drives the only time-based rule of the
 * retention planner: a bundle the server cannot map to anything is kept until {@code dropbox.retention.unknown-ttl}
 * after its first sighting, then expired. Sightings of IDs that disappear from a newer (full-snapshot) inventory are
 * pruned, so the table stays bounded by the dropbox's current holdings.
 */
@Entity
@Table(name = "inventory_sighting")
@NoArgsConstructor
@AllArgsConstructor
public class InventorySighting {
    @EmbeddedId
    public InventorySightingId id;

    /** epoch milliseconds of the first inventory that listed this ID */
    public long firstSeenMs;
}
