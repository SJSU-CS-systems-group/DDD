package net.discdd.server;

import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import net.discdd.grpc.ConnectionData;
import net.discdd.grpc.ResponseStatus;
import net.discdd.grpc.ServiceAdapterRegistryServiceGrpc;
import net.discdd.server.repository.RegisteredAppAdapterRepository;

import java.util.logging.Logger;

import static java.util.logging.Level.INFO;

@GrpcService
public class ServiceAdapterRegistryService extends ServiceAdapterRegistryServiceGrpc.ServiceAdapterRegistryServiceImplBase {
    private static final Logger logger = Logger.getLogger(ServiceAdapterRegistryService.class.getName());
    final private RegisteredAppAdapterRepository registeredAppAdapterRepository;

    ServiceAdapterRegistryService(final RegisteredAppAdapterRepository registeredAppAdapterRepository) {
        this.registeredAppAdapterRepository = registeredAppAdapterRepository;
    }

    @Override
    public void checkAdapterRegistration(ConnectionData connectionData, StreamObserver<ResponseStatus> responseObserver) {
        logger.log(INFO, String.format("Checking %s to %s", connectionData.getAppName(), connectionData.getUrl()));
        var registeredApp = registeredAppAdapterRepository.findByAppId(connectionData.getAppName());
        if (registeredApp.isPresent()) {
            if (registeredApp.get().getAddress().equals(connectionData.getUrl())) {
                responseObserver.onNext(ResponseStatus.newBuilder().setCode(0).setMessage("OK").build());
            } else {
                responseObserver.onNext(ResponseStatus.newBuilder().setCode(2).setMessage(
                        "A different address is registered for " + connectionData.getAppName()).build());
            }
        } else {
            responseObserver.onNext(ResponseStatus.newBuilder().setCode(1).setMessage("No Such App").build());
        }
        responseObserver.onCompleted();
    }
}
