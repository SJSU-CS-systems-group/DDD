package com.ddd.server.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "AppData")
@Table(name = "app_data")
public class AppData {
    @Id
    @Column(
            name = "app_name",
            nullable = false,
            columnDefinition = "VARCHAR(100)"
    )
    private String appName;

    @Column(
            name = "client_id",
            nullable = false,
            columnDefinition = "VARCHAR(256)"
    )
    private String clientId;

    @Column(
            name = "adu_id",
            nullable = false,
            columnDefinition = "INT UNSIGNED"
    )
    private int aduId;

    @Column(
            name = "direction",
            columnDefinition = "VARCHAR(4)"
    )
    private String direction;

    public AppData(String appName, String clientId, int aduId, String direction) {
        this.appName = appName;
        this.clientId = clientId;
        this.aduId = aduId;
        this.direction = direction;
    }

    public AppData() {}

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public int getAduId() {
        return aduId;
    }

    public void setAduId(int aduId) {
        this.aduId = aduId;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    @Override
    public String toString() {
        return "AppDataTable{" +
                "appName='" + appName + '\'' +
                ", clientId='" + clientId + '\'' +
                ", aduId=" + aduId +
                ", direction='" + direction + '\'' +
                '}';
    }
}
