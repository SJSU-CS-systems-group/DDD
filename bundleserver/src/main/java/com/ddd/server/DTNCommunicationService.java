package com.ddd.server;

import com.ddd.server.repository.RegisteredAppAdapterRepository;
import com.ddd.server.repository.entity.RegisteredAppAdapter;
import com.ddd.server.storage.MySQLConnection;
import edu.sjsu.dtn.server.communicationservice.*;
import io.grpc.stub.StreamObserver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

@Component
public class DTNCommunicationService extends DTNCommunicationGrpc.DTNCommunicationImplBase {
    @Autowired
    private static Environment env;

    @Autowired
    private RegisteredAppAdapterRepository registeredAppAdapterRepository;

    @Autowired
    MySQLConnection mysql;

    public DTNCommunicationService(MySQLConnection mysql) {
        this.mysql = mysql;
    }

    @Override
    public void registerAdapter(ConnectionData connectionData, StreamObserver<ResponseStatus> responseObserver) {
        System.out.println("Testing server from Python client");

        RegisteredAppAdapter newAppAdapter =
                new RegisteredAppAdapter(connectionData.getAppName(), connectionData.getUrl());

        registeredAppAdapterRepository.save(newAppAdapter);

        responseObserver.onCompleted();
    }
}
