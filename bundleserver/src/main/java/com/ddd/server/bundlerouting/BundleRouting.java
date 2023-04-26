package com.ddd.server.bundlerouting;

import java.sql.SQLException;
import java.util.List;
import org.springframework.stereotype.Service;

import com.ddd.server.bundlerouting.RoutingExceptions.ClientMetaDataFileException;

@Service
public class BundleRouting {

  ServerRouting routingTable = null;

  public BundleRouting() throws SQLException
  {
      routingTable = new ServerRouting();
  }

  public List<String> getClients(String transportId) throws SQLException {
      return routingTable.getClients(transportId);
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
