package com.ddd.server.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "ClientDataChanged")
@Table(name = "client_data_changed")
public class ClientDataChanged {
    @Id
    @Column(name = "client_id", nullable = false, columnDefinition = "VARCHAR(256)")
    private String clientId;

    @Column(name = "has_new_data", nullable = false, columnDefinition = "TINYINT(1)")
    private int hasNewData;

    public ClientDataChanged(String clientId, int hasNewData) {
        this.clientId = clientId;
        this.hasNewData = hasNewData;
    }

    public ClientDataChanged() {}

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public int getHasNewData() {
        return hasNewData;
    }

    public void setHasNewData(int hasNewData) {
        this.hasNewData = hasNewData;
    }

    @Override
    public String toString() {
        return "ClientDataChangedTable{" + "clientId='" + clientId + '\'' + ", hasNewData=" + hasNewData + '}';
    }
}
