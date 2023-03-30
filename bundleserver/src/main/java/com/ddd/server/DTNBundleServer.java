package com.ddd.server;

import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;

import com.ddd.server.service.BundleServerServiceImpl;

public class DTNBundleServer {
    public int port=8080;
    private final Server server;
    public DTNBundleServer(int port) {
        this(ServerBuilder.forPort(port), port);
    }

    public DTNBundleServer(ServerBuilder<?> serverBuilder, int port) {
        this.port = port;
        server = serverBuilder.addService(new DTNCommunicationService())
                .addService(new BundleServerServiceImpl())
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

    public static void begin(){
        try {
            DTNBundleServer admServer = new DTNBundleServer(8080);
            admServer.start();
            admServer.blockUntilShutdown();
        }catch (Exception ex){
            ex.printStackTrace();
        }
    }
}
