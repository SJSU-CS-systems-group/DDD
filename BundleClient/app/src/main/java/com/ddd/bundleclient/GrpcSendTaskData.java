package com.ddd.bundleclient;

public class GrpcSendTaskData {
    private String host;
    private int port;
    private String status;

    public GrpcSendTaskData(String host, int port) {
        this.host = host;
        this.port = port;
        this.status = null;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getStatus() {
        return port;
    }

    public void setStatus(String newStatus) {
        this.status = newStatus;
    }
}
