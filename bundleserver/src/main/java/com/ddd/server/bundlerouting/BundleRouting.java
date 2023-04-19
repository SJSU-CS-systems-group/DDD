package com.ddd.server.bundlerouting;

import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class BundleRouting {

  public List<String> getClients(String transportId) {
    return Arrays.asList(new String[] {"6h8nBreZglzxHazEbMajporeLfE="});
  }

  public void addClient(String clientId, int windowLength) {}

  /*
   * clientMetaDataPath: path of routing metadata file
   * */
  public void processClientMetaData(String clientMetaDataPath, String clientID) {}

  public void updateClientWindow(String clientID, String bundleID) {}
}
