package com.ddd.server;

import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ddd.server.service.BundleServerServiceImpl;

// @Service
public class DTNBundleServer {
    public int port=8080;
    private final Server server;
    // @Autowired
    BundleServerServiceImpl bundleServerServiceImpl;
    public DTNBundleServer() {
        this(ServerBuilder.forPort(8080), 8080);
        try {
            this.start();
            this.blockUntilShutdown();
        }catch (Exception ex){
            ex.printStackTrace();
        }
    }

    public DTNBundleServer(ServerBuilder<?> serverBuilder, int port) {
        this.port = port;
        server = serverBuilder.addService(new DTNCommunicationService())
                .addService(bundleServerServiceImpl)
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

    // public static void begin(){
    //     try {
    //         DTNBundleServer admServer = new DTNBundleServer(8080);
    //         admServer.start();
    //         admServer.blockUntilShutdown();
    //     }catch (Exception ex){
    //         ex.printStackTrace();
    //     }
    // }
}
