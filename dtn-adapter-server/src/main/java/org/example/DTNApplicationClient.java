package org.example;

import edu.sjsu.dtn.server.communicationservice.ConnectionData;
import edu.sjsu.dtn.server.communicationservice.DTNCommunicationGrpc;
import edu.sjsu.dtn.server.communicationservice.ResponseStatus;
import io.grpc.*;


public class DTNApplicationClient {
    /*
    * Have methods to communicate with DTN bundle server
    * */
    private final DTNCommunicationGrpc.DTNCommunicationBlockingStub blockingStub;
    private final DTNCommunicationGrpc.DTNCommunicationStub asyncStub;

    /** Construct client for accessing DTNCommunication server using the existing channel. */
    public DTNApplicationClient(String ipAddress, int port) {
        Channel channel = ManagedChannelBuilder.forAddress(ipAddress, port)
                .usePlaintext()
                .build();
        blockingStub = DTNCommunicationGrpc.newBlockingStub(channel);
        asyncStub = DTNCommunicationGrpc.newStub(channel);
    }
    public static void main(String[] args) {
        String target = "localhost:8080";
        String ip;
        int port;
        if (args.length > 0) {
            if ("--help".equals(args[0])) {
                System.err.println("Usage: [target]");
                System.err.println("");
                System.err.println("  target  The server to connect to. Defaults to " + target);
                System.exit(1);
            }
            target = args[0];
        }
        ip=target.split(":")[0];
        port=Integer.parseInt(target.split(":")[1]);
        System.out.println(target+", "+ip+", "+port);

        DTNApplicationClient client = new DTNApplicationClient(ip, port);

        ConnectionData data = ConnectionData.newBuilder()
                .setAppName("com.android.mysignal")
                .setUrl("localhost:8090")
                .build();
        try {
            ResponseStatus status = client.blockingStub.registerAdapter(data);
            System.out.println("response: "+status.getMessage());
        } catch (StatusRuntimeException e) {
            e.printStackTrace();
        }
    }
}
