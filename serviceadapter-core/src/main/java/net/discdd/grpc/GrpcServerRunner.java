package net.discdd.grpc;

import io.grpc.BindableService;
import io.grpc.Server;
import net.discdd.tls.DDDNettyTLS;
import net.discdd.tls.GrpcSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.logging.Level.INFO;

@Service
public class GrpcServerRunner implements CommandLineRunner {
    private static final Logger logger = Logger.getLogger(GrpcServerRunner.class.getName());
    static List<BindableService> services;
    @Autowired
    ApplicationContext context;
    int BUNDLE_SERVER_PORT;
    private Server server;
    private final GrpcSecurity grpcSecurity;
    private Thread awaitThread;
    private final Executor executor;

    public GrpcServerRunner(@Value("${ssl-grpc.server.port}") int port,
                            GrpcSecurity grpcSecurity,
                            @Qualifier("grpcExecutor") Executor executor) {
        BUNDLE_SERVER_PORT = port;
        this.grpcSecurity = grpcSecurity;
        this.executor = executor;
    }

    @Override
    public void run(String... args) throws Exception {
        services = context.getBeansWithAnnotation(GrpcService.class)
                .values()
                .stream()
                .map(o -> (BindableService) o)
                .collect(Collectors.toList());

        // make sure we have a spring aware thread pool executor
        server = DDDNettyTLS.createGrpcServer(executor,
                                              grpcSecurity.getGrpcKeyPair(),
                                              grpcSecurity.getGrpcCert(),
                                              BUNDLE_SERVER_PORT,
                                              services.toArray(new BindableService[0]));

        try {
            server.start();
            logger.log(INFO, "gRPC Server started on port " + BUNDLE_SERVER_PORT);

            awaitThread = new Thread(() -> {
                try {
                    server.awaitTermination();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            awaitThread.setName("grpc-server-await-thread");
            awaitThread.setDaemon(false);
            awaitThread.start();

        } catch (Exception e) {
            logger.log(INFO, "gRPC Server failed to start on port " + BUNDLE_SERVER_PORT);
        }
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            server.shutdown();
        }
    }
}