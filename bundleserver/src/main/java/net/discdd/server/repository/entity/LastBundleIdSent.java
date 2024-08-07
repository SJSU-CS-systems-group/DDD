package net.discdd.server.repository.entity;

import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

@Entity(name = "LastBundleIdSent")
@Table(name = "last_bundle_id_sent")
public class LastBundleIdSent {

    @Id
    @Column(name = "client_id", nullable = false, columnDefinition = "TEXT")
    @NotBlank
    private String clientId;

    @Column(name = "bundle_id", nullable = false, columnDefinition = "TEXT")
    @NotBlank
    private String bundleId;

    public LastBundleIdSent() {}

    public LastBundleIdSent(String clientId, String bundleId) {
        this.clientId = clientId;
        this.bundleId = bundleId;
    }

    public String getClientId() {
        return this.clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getBundleId() {
        return this.bundleId;
    }

    public void setBundleId(String bundleId) {
        this.bundleId = bundleId;
    }
}
