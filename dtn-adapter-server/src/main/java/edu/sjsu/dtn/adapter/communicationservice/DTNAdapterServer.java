package edu.sjsu.dtn.adapter.communicationservice;

import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import edu.sjsu.dtn.adapter.signal.SignalCLIConnector;
import edu.sjsu.dtn.storage.FileStoreHelper;

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
//        try {
//    		SignalCLIConnector.performRegistration("1", FileStoreHelper.getStringFromFile("/Users/adityasinghania/Downloads/1.txt").getBytes());
//		} catch (IOException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		} catch (Exception e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
    	try {
            DTNAdapterServer admServer = new DTNAdapterServer(8090);
            admServer.start();
            System.out.println("Running");
            admServer.blockUntilShutdown();
        }catch (Exception ex){
            ex.printStackTrace();
        }
    }
}
