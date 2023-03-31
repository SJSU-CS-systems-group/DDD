package com.ddd.server.applicationdatamanager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.ddd.model.ADU;
import com.ddd.model.Bundle;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

class StateManager {

  private static final String LARGEST_ADU_ID_RECEIVED =
      "/Users/adityasinghania/Downloads/Data/Shared/DB/LARGEST_ADU_ID_RECEIVED.json";

  private static final String SENT_BUNDLE_DETAILS =
      "/Users/adityasinghania/Downloads/Data/Shared/DB/SENT_BUNDLE_DETAILS.json";

  private static final String LARGEST_ADU_ID_DELIVERED =
      "/Users/adityasinghania/Downloads/Data/Shared/DB/LARGEST_ADU_ID_DELIVERED.json";

  private static final String LAST_SENT_BUNDLE_STRUCTURE =
      "/Users/adityasinghania/Downloads/Data/Shared/DB/LAST_SENT_BUNDLE_STRUCTURE.json";

  private DataStoreAdaptor dataStoreAdaptor;

  public StateManager() {
    this.dataStoreAdaptor =
        new DataStoreAdaptor("/Users/adityasinghania/Downloads/Data");
    try {
      File sentBundleDetails = new File(SENT_BUNDLE_DETAILS);
      sentBundleDetails.getParentFile().mkdirs();
      sentBundleDetails.createNewFile();
      File largestADUIdReceived = new File(LARGEST_ADU_ID_RECEIVED);
      largestADUIdReceived.createNewFile();
      File largestADUIdDelivered = new File(LARGEST_ADU_ID_DELIVERED);
      largestADUIdDelivered.createNewFile();
      File lastSentBundleStructure = new File(LAST_SENT_BUNDLE_STRUCTURE);
      lastSentBundleStructure.createNewFile();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /* Largest ADU ID received */

  private void writeLargestADUIdReceivedDetails(
      Map<String, Map<String, Long>> largestADUIdReceivedDetails) {
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    String jsonString = gson.toJson(largestADUIdReceivedDetails);
    try (FileWriter writer = new FileWriter(new File(LARGEST_ADU_ID_RECEIVED))) {
      writer.write(jsonString);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private Map<String, Map<String, Long>> getLargestADUIdReceivedDetails() {
    Gson gson = new Gson();
    Map<String, Map<String, Long>> ret = new HashMap<>();
    try {
      Type mapType = new TypeToken<Map<String, Map<String, Long>>>() {}.getType();
      ret = gson.fromJson(new FileReader(LARGEST_ADU_ID_RECEIVED), mapType);
    } catch (JsonSyntaxException e) {
      e.printStackTrace();
    } catch (JsonIOException e) {
      e.printStackTrace();
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    }
    return ret;
  }

  public Long largestADUIdReceived(String clientId, String appId) {
    Long ret = null;
    Map<String, Map<String, Long>> receivedADUIds = this.getLargestADUIdReceivedDetails();
    Map<String, Long> map = receivedADUIds.get(clientId);
    if (map != null) {
      ret = map.get(appId);
    }
    return ret;
  }

  public void updateLargestADUIdReceived(String clientId, String appId, Long aduId) {
    Map<String, Map<String, Long>> largestADUIdReceivedDetails =
        this.getLargestADUIdReceivedDetails();
    Map<String, Long> clientDetails =
        largestADUIdReceivedDetails.getOrDefault(clientId, new HashMap<>());
    clientDetails.put(appId, aduId);
    largestADUIdReceivedDetails.put(clientId, clientDetails);
    this.writeLargestADUIdReceivedDetails(largestADUIdReceivedDetails);
  }

  /* Largest ADU ID Delivered Details*/

  private Map<String, Map<String, Long>> getLargestADUIdDeliveredDetails() {
    Gson gson = new Gson();
    Map<String, Map<String, Long>> ret = new HashMap<>();
    try {
      Type mapType = new TypeToken<Map<String, Map<String, Long>>>() {}.getType();
      ret = gson.fromJson(new FileReader(LARGEST_ADU_ID_DELIVERED), mapType);
    } catch (JsonSyntaxException e) {
      e.printStackTrace();
    } catch (JsonIOException e) {
      e.printStackTrace();
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    }
    return ret;
  }

  private void writeLargestADUIdDeliveredDetails(
      Map<String, Map<String, Long>> largestADUIdDeliveredDetails) {
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    String jsonString = gson.toJson(largestADUIdDeliveredDetails);
    try (FileWriter writer = new FileWriter(new File(LARGEST_ADU_ID_DELIVERED))) {
      writer.write(jsonString);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public Long getLargestADUIdDeliveredByAppId(String clientId, String appId) {
    Map<String, Map<String, Long>> largestADUIdDeliveredDetails =
        this.getLargestADUIdDeliveredDetails();
    Long ret = null;
    if (largestADUIdDeliveredDetails.get(clientId) != null) {
      ret = largestADUIdDeliveredDetails.get(clientId).get(appId);
    }
    return ret;
  }

  /* Sent bundle Details*/
  private Map<String, Map<String, Long>> getSentBundleDetails() {
    Gson gson = new Gson();
    Map<String, Map<String, Long>> ret = new HashMap<>();
    try {
      Type mapType = new TypeToken<Map<String, Map<String, Long>>>() {}.getType();
      ret = gson.fromJson(new FileReader(new File(SENT_BUNDLE_DETAILS)), mapType);
    } catch (JsonSyntaxException e) {
      e.printStackTrace();
    } catch (JsonIOException e) {
      e.printStackTrace();
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    }
    return ret;
  }

  private void writeSentBundleDetails(Map<String, Map<String, Long>> sentBundleDetails) {
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    String jsonString = gson.toJson(sentBundleDetails);
    try (FileWriter writer = new FileWriter(new File(SENT_BUNDLE_DETAILS))) {
      writer.write(jsonString);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public void registerSentBundleDetails(Bundle sentBundle) {
    if (sentBundle.getADUs().isEmpty()) {
      return;
    }
    Map<String, Map<String, Long>> sentBundleDetails = this.getSentBundleDetails();
    if (sentBundleDetails.containsKey(sentBundle.getBundleId())) {
      return;
    }
    Map<String, Long> bundleDetails = new HashMap<>();
    for (ADU adu : sentBundle.getADUs()) {
      String appId = adu.getAppId();
      Long aduId = adu.getADUId();

      if (!bundleDetails.containsKey(appId) || bundleDetails.get(appId) < aduId) {
        bundleDetails.put(appId, aduId);
      }
    }
    sentBundleDetails.put(sentBundle.getBundleId(), bundleDetails);
    this.writeSentBundleDetails(sentBundleDetails);
  }

  public void processAcknowledgement(String clientId, String bundleId) {
    Map<String, Map<String, Long>> sentBundleDetails = this.getSentBundleDetails();
    Map<String, Long> sentDetails = sentBundleDetails.getOrDefault(bundleId, new HashMap<>());
    Map<String, Map<String, Long>> largestADUIdDeliveredDetails =
        this.getLargestADUIdDeliveredDetails();
    Map<String, Long> largestADUDetails =
        largestADUIdDeliveredDetails.getOrDefault(clientId, new HashMap<>());
    for (String appId : sentDetails.keySet()) {
      this.dataStoreAdaptor.deleteADUs(clientId, appId, sentDetails.get(appId));
      Long aduId = sentDetails.get(appId);
      if (!largestADUDetails.containsKey(appId) || aduId > largestADUDetails.get(appId)) {
        largestADUDetails.put(appId, aduId);
      }
    }
    if (!largestADUDetails.keySet().isEmpty()) {
      largestADUIdDeliveredDetails.put(clientId, largestADUDetails);
      this.writeLargestADUIdDeliveredDetails(largestADUIdDeliveredDetails);
    }
    System.out.println(
        "[SM] Processed acknowledgement for sent bundle id "
            + bundleId
            + " corresponding to client "
            + clientId);
  }
}

public class ApplicationDataManager {

  private StateManager stateManager;
  private DataStoreAdaptor dataStoreAdaptor;

  private Long APP_DATA_SIZE_LIMIT = 10000L;

  private static final String REGISTERED_APP_IDS =
      "/Users/adityasinghania/Downloads/Data/Shared/DB/REGISTERED_APP_IDS.txt";

  public ApplicationDataManager() {
    this.stateManager = new StateManager();
    this.dataStoreAdaptor = new DataStoreAdaptor("/Users/adityasinghania/Downloads/Data");
  }

  public List<String> getRegisteredAppIds() {
    List<String> registeredAppIds = new ArrayList<>();
    try (BufferedReader bufferedReader =
        new BufferedReader(new FileReader(new File(REGISTERED_APP_IDS)))) {
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

  public void processAck(String clientId, String bundleId) {
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

      if (appIdToADUMap.containsKey(adu.getClientId())) {
        appIdToADUMap.get(adu.getClientId()).add(adu);
      } else {
        appIdToADUMap.put(adu.getClientId(), new ArrayList<>());
        appIdToADUMap.get(adu.getClientId()).add(adu);
      }
    }
    for(String appId: appIdToADUMap.keySet()){
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
        if (adu.getSize() + cumulativeSize > this.APP_DATA_SIZE_LIMIT) {
          break;
        }
        res.add(adu);
        cumulativeSize += adu.getSize();
      }
    }
    return res;
  }

  public void notifyBundleGenerated(Bundle bundle) {
    this.stateManager.registerSentBundleDetails(bundle);
  }
}
