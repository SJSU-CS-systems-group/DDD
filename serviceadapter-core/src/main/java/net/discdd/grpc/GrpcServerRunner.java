package net.discdd.grpc;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.logging.Level.INFO;

@Service
public class GrpcServerRunner implements CommandLineRunner {
    private static final Logger logger = Logger.getLogger(GrpcServerRunner.class.getName());
    static List<BindableService> services;
    private Server server;
    @Autowired
    ApplicationContext context;

    int BUNDLE_SERVER_PORT;

    public GrpcServerRunner(@Value("${ssl-grpc.server.port}") int port) {
        BUNDLE_SERVER_PORT = port;
    }

    @Override
    public void run(String... args) throws Exception {
        services = context.getBeansWithAnnotation(GrpcService.class).values().stream().map(o -> (BindableService) o).collect(Collectors.toList());

        var serverBuilder = NettyServerBuilder.forPort(BUNDLE_SERVER_PORT);

        for (var service : services) {
            serverBuilder.addService(service);
        }

        server = serverBuilder.build();

        server.start();
        logger.log(INFO, "gRPC Server started on port " + BUNDLE_SERVER_PORT);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down gRPC Server...");
            if (server != null) {
                server.shutdown();
            }
        }));
        if (!isRunningInMaven()) {
            server.awaitTermination();
        }
    }

    private boolean isRunningInMaven() {
        return System.getProperty("surefire.test.class.path") != null || System.getProperty("maven.home") != null;
    }
}