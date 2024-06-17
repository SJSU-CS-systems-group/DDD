package com.ddd.server;

import com.ddd.server.repository.RegisteredAppAdapterRepository;
import com.ddd.server.repository.entity.RegisteredAppAdapter;
import com.ddd.server.storage.MySQLConnection;
import io.grpc.stub.StreamObserver;
import net.discdd.server.ConnectionData;
import net.discdd.server.ResponseStatus;
import net.discdd.server.ServiceAdapterRegistryGrpc;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.logging.Logger;

import static java.util.logging.Level.INFO;

@Component
public class ServiceAdapterRegistryService extends ServiceAdapterRegistryGrpc.ServiceAdapterRegistryImplBase {
    private static final Logger logger = Logger.getLogger(DTNCommunicationService.class.getName());

    @Autowired
    private RegisteredAppAdapterRepository registeredAppAdapterRepository;

    @Autowired
    MySQLConnection mysql;

    public ServiceAdapterRegistryService(MySQLConnection mysql) {
        this.mysql = mysql;
    }

    @Override
    public void registerAdapter(ConnectionData connectionData, StreamObserver<ResponseStatus> responseObserver) {
        logger.log(INFO,"Testing server from Python client");

        RegisteredAppAdapter newAppAdapter =
                new RegisteredAppAdapter(connectionData.getAppName(), connectionData.getUrl());

        registeredAppAdapterRepository.save(newAppAdapter);

        responseObserver.onCompleted();
    }
}
