package com.ddd.server;

import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;
import java.net.URL;

public class DTNBundleServer {
    public int port=8080;
    private final Server server;
    public DTNBundleServer(int port) {
        this(ServerBuilder.forPort(port), port);
    }

    public DTNBundleServer(ServerBuilder<?> serverBuilder, int port) {
        this.port = port;
        server = serverBuilder.addService(new DTNCommunicationService())
                .build();
    }
    public void start() throws IOException {
        server.start();
    }

    private void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    public static void main(String[] args){
        try {
            DTNBundleServer admServer = new DTNBundleServer(8080);
            admServer.start();
            admServer.blockUntilShutdown();
        }catch (Exception ex){
            ex.printStackTrace();
        }
    }
}
