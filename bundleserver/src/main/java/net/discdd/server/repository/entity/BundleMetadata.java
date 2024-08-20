package net.discdd.server.repository.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Entity
@AllArgsConstructor
@NoArgsConstructor
public class BundleMetadata {
    @Id
    public String encryptedBundleId;

    /** the bundle counter that generated this encryptedBundleId */
    public long bundleCounter;
    /** the client id that generated this encryptedBundleId */
    public String clientId;
    /** the ackCounter sent in the bundle. it represents the last bundle counter received from the client */
    public long ackCounter;
}
