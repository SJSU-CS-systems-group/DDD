package com.ddd.server;

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
    MySQLConnection mysql;

    public DTNCommunicationService (MySQLConnection mysql) {
        this.mysql = mysql;
    }

    @Override
    public void registerAdapter(ConnectionData connectionData,
                                StreamObserver<ResponseStatus> responseObserver){
        System.out.println("Testing server from Python client");

        try {
            System.out.println("Test DB connection");

            Connection con = mysql.GetConnection();
            Statement stmt = con.createStatement();

            System.out.println("Test DB query");

            stmt.executeUpdate("insert into registered_app_adapter_table (app_id, address) values (" +
                    "'"+connectionData.getAppName()+"', '"+connectionData.getUrl()+"');");

            System.out.println("Query done");

            con.close();
            responseObserver.onNext(ResponseStatus.newBuilder().setCode(0).build());
        } catch (Exception ex){
            ex.printStackTrace();
        }
        responseObserver.onCompleted();
    }
}
