package com.ddd.server;

import com.ddd.server.repository.RegisteredAppAdapterRepository;
import com.ddd.server.repository.entity.RegisteredAppAdapter;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import net.discdd.server.ConnectionData;
import net.discdd.server.ResponseStatus;
import net.discdd.server.ServiceAdapterRegistryGrpc;

import java.util.logging.Logger;

import static java.util.logging.Level.INFO;

@GrpcService
public class ServiceAdapterRegistryService extends ServiceAdapterRegistryGrpc.ServiceAdapterRegistryImplBase {
    private static final Logger logger = Logger.getLogger(ServiceAdapterRegistryService.class.getName());

    ServiceAdapterRegistryService(final RegisteredAppAdapterRepository registeredAppAdapterRepository) {
        this.registeredAppAdapterRepository = registeredAppAdapterRepository;
    }

    final private RegisteredAppAdapterRepository registeredAppAdapterRepository;

    @Override
    public void registerAdapter(ConnectionData connectionData, StreamObserver<ResponseStatus> responseObserver) {
        logger.log(INFO, String.format("Checking %s to %s", connectionData.getAppName(), connectionData.getUrl()));
        RegisteredAppAdapter newAppAdapter =
                new RegisteredAppAdapter(connectionData.getAppName(), connectionData.getUrl());

        registeredAppAdapterRepository.save(newAppAdapter);

        responseObserver.onNext(ResponseStatus.newBuilder().setCode(0).setMessage("OK").build());
        responseObserver.onCompleted();
    }
}
