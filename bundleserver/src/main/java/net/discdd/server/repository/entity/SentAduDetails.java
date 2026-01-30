package net.discdd.server.repository.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import org.hibernate.annotations.UuidGenerator;

import lombok.NoArgsConstructor;

@Entity
@NoArgsConstructor
public class SentAduDetails {
    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    public String bundleId;
    public long ClientBundleCounter;
    public String appId;
    public long aduIdRangeStart;
    public long aduIdRangeEnd;

    public SentAduDetails(String bundleId,
                          String clientId,
                          long ClientBundleCounter,
                          String appId,
                          long aduIdRangeStart,
                          long aduIdRangeEnd) {
        this.bundleId = bundleId;
        this.ClientBundleCounter = ClientBundleCounter;
        this.appId = appId;
        this.aduIdRangeStart = aduIdRangeStart;
        this.aduIdRangeEnd = aduIdRangeEnd;
    }
}
