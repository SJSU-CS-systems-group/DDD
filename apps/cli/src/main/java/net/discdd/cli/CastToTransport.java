package net.discdd.cli;

import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "cast-to-transport", description = "cast a message string to a transport")
public class CastToTransport implements Callable<Void> {

    @CommandLine.Option(names = "--message", required = true, description = "message to send to transport")
    private String message;

    @CommandLine.Option(names = "--transportid", required = true, description = "the id of the transport")
    private String transportId;

    @Override
    public Void call() {
        try {
            System.out.println("Message: " + message + "\tTransport ID: " + transportId);
        }
        catch(Exception e){
            e.printStackTrace();
        }
        return null;
    }

}
