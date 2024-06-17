package com.ddd.server;

import com.ddd.server.repository.RegisteredAppAdapterRepository;
import com.ddd.server.repository.entity.RegisteredAppAdapter;
import com.ddd.server.storage.MySQLConnection;
import edu.sjsu.dtn.server.communicationservice.*;
import io.grpc.stub.StreamObserver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import static java.util.logging.Level.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.logging.Logger;

@Component
public class DTNCommunicationService extends DTNCommunicationGrpc.DTNCommunicationImplBase {
    private static final Logger logger = Logger.getLogger(DTNCommunicationService.class.getName());
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
        logger.log(INFO, "Testing server from Python client");

        RegisteredAppAdapter newAppAdapter =
                new RegisteredAppAdapter(connectionData.getAppName(), connectionData.getUrl());

        registeredAppAdapterRepository.save(newAppAdapter);

        responseObserver.onCompleted();
    }
}
