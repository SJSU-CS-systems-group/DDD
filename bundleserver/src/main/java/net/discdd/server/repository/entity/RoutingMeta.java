package net.discdd.server.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/** Named epoch-millisecond markers for routing; {@code legacy_import_done_ms} records the one-time legacy import. */
@Entity
@Table(name = "routing_meta")
@NoArgsConstructor
@AllArgsConstructor
public class RoutingMeta {
    public static final String LEGACY_IMPORT_DONE_MS = "legacy_import_done_ms";

    @Id
    @Column(name = "meta_key", length = 64)
    public String metaKey;

    @Column(name = "value_ms", nullable = false)
    public long valueMs;
}
