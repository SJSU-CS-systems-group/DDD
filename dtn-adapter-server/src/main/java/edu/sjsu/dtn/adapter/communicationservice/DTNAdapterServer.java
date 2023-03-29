package edu.sjsu.dtn.adapter.communicationservice;

import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;

public class DTNAdapterServer {
    public int port=8080;
    private final Server server;
    public DTNAdapterServer(int port) {
        this(ServerBuilder.forPort(port), port);
    }

    public DTNAdapterServer(ServerBuilder<?> serverBuilder, int port) {
        this.port = port;
        server = serverBuilder.addService(new DTNAdapterService())
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
            DTNAdapterServer admServer = new DTNAdapterServer(8090);
            admServer.start();
            admServer.blockUntilShutdown();
        }catch (Exception ex){
            ex.printStackTrace();
        }
    }
}
