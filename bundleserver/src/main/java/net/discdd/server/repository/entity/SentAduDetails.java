package net.discdd.server.repository.entity;

import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Entity(name = "SentAduDetails")
@Table(name = "sent_adu_details")
public class SentAduDetails {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "bundle_id", nullable = false, columnDefinition = "TEXT")
    @NotBlank
    private String bundleId;

    @Column(name = "app_id", nullable = false, columnDefinition = "TEXT")
    @NotBlank
    private String appId;

    @Column(name = "adu_id_range_start", nullable = false, columnDefinition = "BIGINT")
    @NotNull
    private Long aduIdRangeStart;

    @Column(name = "adu_id_range_end", nullable = false, columnDefinition = "BIGINT")
    @NotNull
    private Long aduIdRangeEnd;

    public SentAduDetails() {}

    public SentAduDetails(String bundleId, String appId, Long aduIdRangeStart, Long aduIdRangeEnd) {
        this.bundleId = bundleId;
        this.appId = appId;
        this.aduIdRangeStart = aduIdRangeStart;
        this.aduIdRangeEnd = aduIdRangeEnd;
    }

    public String getId() {
        return this.id.toString();
    }

    public void setId(String id) {
        this.id = UUID.fromString(id);
    }

    public String getBundleId() {
        return this.bundleId;
    }

    public void setBundleId(String bundleId) {
        this.bundleId = bundleId;
    }

    public String getAppId() {
        return this.appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public Long getAduIdRangeStart() {
        return this.aduIdRangeStart;
    }

    public void setAduIdRangeStart(Long aduIdRangeStart) {
        this.aduIdRangeStart = aduIdRangeStart;
    }

    public Long getAduIdRangeEnd() {
        return this.aduIdRangeEnd;
    }

    public void setAduIdRangeEnd(Long aduIdRangeEnd) {
        this.aduIdRangeEnd = aduIdRangeEnd;
    }
}
