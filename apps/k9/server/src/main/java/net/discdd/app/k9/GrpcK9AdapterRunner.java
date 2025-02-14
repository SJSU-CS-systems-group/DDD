package net.discdd.app.k9;

import io.grpc.BindableService;
import io.grpc.Server;
import net.discdd.grpc.GrpcService;
import net.discdd.security.AdapterSecurity;
import net.discdd.tls.DDDNettyTLS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.logging.Level.INFO;

@Component
public class GrpcK9AdapterRunner implements CommandLineRunner {
    private static final Logger logger = Logger.getLogger(GrpcK9AdapterRunner.class.getName());;
    static List<BindableService> services;
    @Autowired
    private AdapterSecurity adapterSecurity;
    private Server server;
    @Autowired
    ApplicationContext context;
    @Value("${grpc.server.port}")
    private int port;

    @Override
    public void run(String... args) throws Exception {
        services = context.getBeansWithAnnotation(GrpcService.class).values().stream().map(o -> (BindableService) o).collect(Collectors.toList());

        server = DDDNettyTLS.createGrpcServer(
                adapterSecurity.getAdapterKeyPair(),
                adapterSecurity.getAdapterCert(),
                port,
                services.toArray(new BindableService[0])
        );

        server.start();
        logger.log(INFO, "gRPC Server started on port " + port);

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
