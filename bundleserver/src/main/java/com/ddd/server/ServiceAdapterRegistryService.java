package com.ddd.server;

import com.ddd.server.repository.RegisteredAppAdapterRepository;
import com.ddd.server.repository.entity.RegisteredAppAdapter;
import com.ddd.server.storage.MySQLConnection;
import io.grpc.stub.StreamObserver;
import net.discdd.server.ConnectionData;
import net.discdd.server.ResponseStatus;
import net.discdd.server.ServiceAdapterRegistryGrpc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ServiceAdapterRegistryService extends ServiceAdapterRegistryGrpc.ServiceAdapterRegistryImplBase {
    @Autowired
    private RegisteredAppAdapterRepository registeredAppAdapterRepository;

    @Autowired
    MySQLConnection mysql;

    public ServiceAdapterRegistryService(MySQLConnection mysql) {
        this.mysql = mysql;
    }

    @Override
    public void registerAdapter(ConnectionData connectionData, StreamObserver<ResponseStatus> responseObserver) {
        RegisteredAppAdapter newAppAdapter =
                new RegisteredAppAdapter(connectionData.getAppName(), connectionData.getUrl());

        registeredAppAdapterRepository.save(newAppAdapter);

        responseObserver.onNext(ResponseStatus.newBuilder().setCode(0).setMessage("OK").build());
        responseObserver.onCompleted();
    }
}
