package com.ddd.server.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "ServerRouting")
@Table(name = "serverroutingtable")
public class ServerRouting {

    @Id
    @Column(name = "transportID", nullable = false, columnDefinition = "VARCHAR(256)")
    private String transportID;

    @Id
    @Column(name = "clientID", nullable = false, columnDefinition = "VARCHAR(256)")
    private String clientID;

    @Column(name = "score", nullable = false, columnDefinition = "VARCHAR(256)")
    private String score;

    public ServerRouting(String transportID, String clientID, String score) {
        this.transportID = transportID;
        this.clientID = clientID;
        this.score = score;
    }

    public String getClientId() {
        return clientID;
    }

    public void setClientId(String clientId) {
        this.clientID = clientId;
    }

    public String getTransportId() {
        return transportID;
    }

    public void setTransportId(String transportId) {
        this.transportID = transportId;
    }

    public String getScore() {
        return score;
    }

    public void setScore(String score) {
        this.score = score;
    }

    @Override
    public String toString() {
        return "ServerRouting{" + "transportID='" + transportID + '\'' + ", clientID='" + clientID + '\'' +
                ", score ='" + score + '\'' + '}';
    }
}
