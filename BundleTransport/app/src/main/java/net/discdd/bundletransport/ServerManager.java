package net.discdd.bundletransport;

import java.io.File;
import java.nio.file.Path;
import java.util.function.Function;

import java.util.logging.Logger;

import static java.util.logging.Level.INFO;

public class ServerManager implements Runnable {

    private static final Logger logger = Logger.getLogger(ServerManager.class.getName());

    //private final static String TAG = "dddTransport";

    private Function<Exception, Void> sendCallback, receiveCallback;
    private Function<Void, Void> connectComplete;
    private GrpcSendTask sendTask;
    private GrpcReceiveTask receiveTask;

    public ServerManager(Path filePath, String host, String port, String transportId,
                         Function<Exception, Void> sendCallback, Function<Exception, Void> receiveCallback,
                         Function<Void, Void> connectComplete) {
        this.sendCallback = sendCallback;
        this.receiveCallback = receiveCallback;
        this.connectComplete = connectComplete;
        this.sendTask =
                new GrpcSendTask(host, Integer.parseInt(port), transportId, filePath.resolve("BundleTransmission/server"));
        this.receiveTask =
                new GrpcReceiveTask(host, Integer.parseInt(port), transportId, filePath.resolve("BundleTransmission/client"));
    }

    @Override
    public void run() {
        sendCallback.apply(sendTask.run());
        receiveCallback.apply(receiveTask.run());
        logger.log(INFO, "Connect server completed");
        connectComplete.apply(null);
    }
}
