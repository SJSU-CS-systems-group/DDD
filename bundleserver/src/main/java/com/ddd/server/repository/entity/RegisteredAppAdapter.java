package com.ddd.server.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "RegisteredAppAdapter")
@Table(name = "registered_app_adapter")
public class RegisteredAppAdapter {
    @Id
    @Column(
            name = "app_id",
            nullable = false,
            columnDefinition = "VARCHAR(100)"
    )
    private String appId;

    @Column(
            name = "address",
            nullable = false,
            columnDefinition = "VARCHAR(200)"
    )
    private String address;

    public RegisteredAppAdapter(String appId, String address) {
        this.appId = appId;
        this.address = address;
    }

    public RegisteredAppAdapter() {}

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    @Override
    public String toString() {
        return "RegisteredAppAdapter{" +
                "appId=" + appId +
                ", address='" + address + '\'' +
                '}';
    }
}
