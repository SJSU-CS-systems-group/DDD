package net.discdd.server.service;

import io.grpc.BindableService;
import io.grpc.Server;
import net.discdd.bundlesecurity.ServerSecurity;
import net.discdd.grpc.GrpcService;
import net.discdd.tls.DDDNettyTLS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.logging.Level.INFO;

@Profile("!test")
@Component
public class GrpcServerRunner implements CommandLineRunner {
    private static final Logger logger = Logger.getLogger(GrpcServerRunner.class.getName());
    @Autowired
    private ServerSecurity serverSecurity;
    static List<BindableService> services;
    private Server server;
    @Autowired
    ApplicationContext context;

    @Override
    public void run(String... args) throws Exception {
        services = context.getBeansWithAnnotation(GrpcService.class).values().stream().map(o -> (BindableService) o).collect(Collectors.toList());

        server = DDDNettyTLS.createGrpcServer(
                serverSecurity.getJavaKeyPair(),
                serverSecurity.getServerCert(),
                7778,
                services.toArray(new BindableService[0])
        );

        server.start();
        logger.log(INFO, "gRPC Server started on port 7778");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down gRPC Server...");
            if (server != null) {
                server.shutdown();
            }
        }));
    }
}
