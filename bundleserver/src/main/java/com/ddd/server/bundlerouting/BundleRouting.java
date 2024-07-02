package com.ddd.server.bundlerouting;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.ddd.server.repository.ServerRoutingRepository;
import com.ddd.server.repository.entity.ServerRouting;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ddd.bundlerouting.RoutingExceptions.ClientMetaDataFileException;

@Service
public class BundleRouting {

    @Autowired
    ServerRoutingRepoTemp routingTable;

    @Autowired
    ServerRoutingRepository serverRoutingRepository;

    public List<String> getClients(String transportId) throws SQLException {
        List<ServerRouting> serverRoutings = serverRoutingRepository.findByServerRoutingIdTransportID(transportId);
        List<String> clientIDs = new ArrayList<>();
        if (serverRoutings != null) {
            for (ServerRouting serverRouting : serverRoutings) {
                clientIDs.add(serverRouting.getServerRoutingId().getClientID());
            }
        }
        return clientIDs;
    }

    public void addClient(String clientId, int windowLength) {}

    /*
     * payloadPath: path of received payload where routing metadata file exists
     */
    public void processClientMetaData(String payloadPath, String transportID, String clientID) throws ClientMetaDataFileException, SQLException {
        routingTable.processClientMetaData(payloadPath, transportID, clientID);
    }

    public void updateClientWindow(String clientID, String bundleID) {}
}
