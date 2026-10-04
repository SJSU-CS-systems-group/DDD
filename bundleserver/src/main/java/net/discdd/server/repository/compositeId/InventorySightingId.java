package net.discdd.server.repository.compositeId;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Primary key of {@code InventorySighting}: one bundle ID, in one direction, on one dropbox. */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class InventorySightingId {
    @Column(name = "dropboxId", nullable = false, length = 255)
    private String dropboxId;
    /** {@code UPSTREAM} or {@code DOWNSTREAM} */
    @Column(name = "direction", nullable = false, length = 16)
    private String direction;
    @Column(name = "bundleId", nullable = false, length = 255)
    private String bundleId;
}
