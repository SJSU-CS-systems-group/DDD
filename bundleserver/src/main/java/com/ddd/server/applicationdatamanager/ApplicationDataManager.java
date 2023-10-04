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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.ddd.model.ADU;
import com.ddd.model.UncompressedPayload;
import com.ddd.server.config.BundleServerConfig;

@Service
public class ApplicationDataManager {

  @Autowired private StateManager stateManager;

  private DataStoreAdaptor dataStoreAdaptor;

  @Autowired private BundleServerConfig bundleServerConfig;

  @Value("${bundle-server.registered-app-ids}")
  private String registeredAppIdsFile;

  @Value("${bundle-server.bunde-store-root}")
  private String rootDataDir;
  
  public ApplicationDataManager() {
    //    this.dataStoreAdaptor = new DataStoreAdaptor(bundleServerConfig.getBundleStoreRoot());
    this.dataStoreAdaptor =
        new DataStoreAdaptor(rootDataDir);
  }
  
  public List<String> getRegisteredAppIds() {
    List<String> registeredAppIds = new ArrayList<>();
    try (BufferedReader bufferedReader =
        new BufferedReader(
            new FileReader(new File(registeredAppIdsFile)))) {
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
            new FileWriter(new File(registeredAppIdsFile)))) {
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

  public void storeADUs(String clientId, String bundleId, List<ADU> adus) {
    System.out.println("[ADM] Store ADUs");
    this.registerRecvdBundleId(clientId, bundleId);
    Map<String, List<ADU>> appIdToADUMap = new HashMap<>();

    List<String> registeredAppIds = this.getRegisteredAppIds();
    for (String registeredAppId: registeredAppIds) {
      appIdToADUMap.put(registeredAppId, new ArrayList<>());
    }
    for (ADU adu : adus) {
      System.out.println("[ADM] "+adu.getADUId());
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
      System.out.println("[ADM] "+appId+" "+appIdToADUMap.get(appId));
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

  public void notifyBundleGenerated(String clientId, UncompressedPayload bundle) {
    this.stateManager.registerSentBundleDetails(clientId, bundle);
  }

  public Optional<UncompressedPayload.Builder> getLastSentBundlePayloadBuilder(String clientId) {
    return this.stateManager.getLastSentBundlePayloadBuilder(clientId);
  }

  private void registerRecvdBundleId(String clientId, String bundleId) {
    this.stateManager.registerRecvdBundleId(clientId, bundleId);
  }

  public Optional<String> getLargestRecvdBundleId(String clientId) {
    return this.stateManager.getLargestRecvdBundleId(clientId);
  }

  public String getClientIdFromSentBundleId(String bundleId) {
    return this.stateManager.getClientIdFromSentBundleId(bundleId);
  }
  
  public void collectDataForClients(String clientId) {
    System.out.println("[ADM] Collecting data for client " + clientId);
    List<String> appIds = this.getRegisteredAppIds();
    for (String appId: appIds) {        
     this.dataStoreAdaptor.prepareData(appId, clientId);
    }
  }
}
