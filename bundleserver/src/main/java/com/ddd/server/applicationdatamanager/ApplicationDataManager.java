package com.ddd.server.applicationdatamanager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ddd.model.ADU;
import com.ddd.model.Bundle;
import com.ddd.server.config.BundleServerConfig;

@Service
public class ApplicationDataManager {

  @Autowired
  private StateManager stateManager;
  
  private DataStoreAdaptor dataStoreAdaptor;
  
  @Autowired
  private BundleServerConfig bundleServerConfig;
  
  public ApplicationDataManager() {
//    this.dataStoreAdaptor = new DataStoreAdaptor(bundleServerConfig.getBundleStoreRoot()); 
    this.dataStoreAdaptor = new DataStoreAdaptor("/Users/adityasinghania/Downloads/Data/Shared");
  }

  public List<String> getRegisteredAppIds() {
    List<String> registeredAppIds = new ArrayList<>();
    try (BufferedReader bufferedReader =
        new BufferedReader(
            new FileReader(new File(this.bundleServerConfig.getRegisteredAppIds())))) {
      String line = "";
      while ((line = bufferedReader.readLine()) != null) {
        String appId = line.trim();
        registeredAppIds.add(appId);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return registeredAppIds;
  }
  
  public void registerAppId(String appId) {
    try (BufferedWriter bufferedWriter =
        new BufferedWriter(
            new FileWriter(new File(this.bundleServerConfig.getRegisteredAppIds())))) {
      bufferedWriter.write(appId);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public void processAcknowledgement(String clientId, String bundleId) {
    if ("HB".equals(bundleId)) {
      return;
    }
    this.stateManager.processAcknowledgement(clientId, bundleId);
  }

  public void storeADUs(String clientId, List<ADU> adus) {
    Map<String, List<ADU>> appIdToADUMap = new HashMap<>();

    for (ADU adu : adus) {
      Long largestAduIdReceived = this.stateManager.largestADUIdReceived(clientId, adu.getAppId());
      if (largestAduIdReceived != null && adu.getADUId() <= largestAduIdReceived) {
        continue;
      }
      this.stateManager.updateLargestADUIdReceived(clientId, adu.getAppId(), adu.getADUId());

      if (appIdToADUMap.containsKey(adu.getAppId())) {
        appIdToADUMap.get(adu.getAppId()).add(adu);
      } else {
        appIdToADUMap.put(adu.getAppId(), new ArrayList<>());
        appIdToADUMap.get(adu.getAppId()).add(adu);
      }
    }
    for (String appId : appIdToADUMap.keySet()) {
      this.dataStoreAdaptor.persistADUsForServer(clientId, appId, appIdToADUMap.get(appId));
    }
  }

  public List<ADU> fetchADUs(String clientId) {
    List<ADU> res = new ArrayList<>();
    for (String appId : this.getRegisteredAppIds()) {
      Long largestAduIdDelivered =
          this.stateManager.getLargestADUIdDeliveredByAppId(clientId, appId);
      Long aduIdStart = (largestAduIdDelivered != null) ? (largestAduIdDelivered + 1) : 1;
      List<ADU> adus = this.dataStoreAdaptor.fetchADUs(clientId, appId, aduIdStart);
      Long cumulativeSize = 0L;
      for (ADU adu : adus) {
        if (adu.getSize() + cumulativeSize
            > this.bundleServerConfig.getApplicationDataManager().getAppDataSizeLimit()) {
          break;
        }
        res.add(adu);
        cumulativeSize += adu.getSize();
      }
    }
    return res;
  }

  public void notifyBundleGenerated(String clientId, Bundle bundle) {
    this.stateManager.registerSentBundleDetails(clientId, bundle);
  }

  public Optional<Bundle.Builder> getLastSentBundleBuilder(String clientId) {
    return this.stateManager.getLastSentBundleBuilder(clientId);
  }
}
