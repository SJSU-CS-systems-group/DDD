package com.ddd.server.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "ServerWindow")
@Table(name = "serverwindow")
public class ServerWindow {
    @Id
    @Column(name = "clientID", nullable = false, columnDefinition = "VARCHAR(256)")
    private String clientID;

    @Column(name = "startCounter", nullable = false, columnDefinition = "VARCHAR(256)")
    private String startCounter;

    @Column(name = "endCounter", nullable = false, columnDefinition = "VARCHAR(256)")
    private String endCounter;

    @Column(name = "windowLength", nullable = false, columnDefinition = "INTEGER")
    private int windowLength;

    public String getStartCounter() {
        return startCounter;
    }

    public void setStartCounter(String startCounter) {
        this.startCounter = startCounter;
    }

    public int getWindowLength() {
        return windowLength;
    }

    public void setWindowLength(int windowLength) {
        this.windowLength = windowLength;
    }

    public ServerWindow(String clientID, String startCounter, String endCounter, int windowLength) {
        this.clientID = clientID;
        this.startCounter = startCounter;
        this.endCounter = endCounter;
        this.windowLength = windowLength;
    }

    public String getClientId() {
        return clientID;
    }

    public void setClientId(String clientId) {
        this.clientID = clientId;
    }

    public String getEndCounter() {
        return endCounter;
    }

    public void setEndCounter(String endCounter) {
        this.endCounter = endCounter;
    }
    @Override
    public String toString() {
        return "ServerWindow{" +
                "clientId='" + clientID + '\'' +
                ", startCounter='" + startCounter + '\'' +
                ", endCounter='" + endCounter + '\'' +
                ", windowLength=" + windowLength +
                '}';
    }
}
