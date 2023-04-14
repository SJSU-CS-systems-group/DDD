package com.ddd.server.applicationdatamanager;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ddd.model.ADU;
import com.ddd.model.Bundle;
import com.ddd.server.config.BundleServerConfig;
import com.ddd.utils.BundleUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

@Service
class StateManager {
  @Autowired
  private BundleServerConfig config;

  private DataStoreAdaptor dataStoreAdaptor;

  public StateManager() {
    this.dataStoreAdaptor = new DataStoreAdaptor("/Users/adityasinghania/Downloads/Data/Shared/");
  }
  
  /* Largest ADU ID received */

  private void writeLargestADUIdReceivedDetails(
      Map<String, Map<String, Long>> largestADUIdReceivedDetails) {
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    String jsonString = gson.toJson(largestADUIdReceivedDetails);
    try (FileWriter writer = new FileWriter(new File(config.getApplicationDataManager().getStateManager().getLargestAduIdReceived()))) {
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
      ret = gson.fromJson(new FileReader(config.getApplicationDataManager().getStateManager().getLargestAduIdReceived()), mapType);
      if (ret == null) {
        ret = new HashMap<>();
      }
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
      ret = gson.fromJson(new FileReader(config.getApplicationDataManager().getStateManager().getLargestAduIdDelivered()), mapType);
      if (ret == null) {
        ret = new HashMap<>();
      }
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
    try (FileWriter writer = new FileWriter(new File(config.getApplicationDataManager().getStateManager().getLargestAduIdDelivered()))) {
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
      ret = gson.fromJson(new FileReader(new File(config.getApplicationDataManager().getStateManager().getSentBundleDetails())), mapType);
      if (ret == null) {
        ret = new HashMap<>();
      }
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
    try (FileWriter writer = new FileWriter(new File(config.getApplicationDataManager().getStateManager().getSentBundleDetails()))) {
      writer.write(jsonString);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private Map<String, Map<String, Object>> getLastSentBundleStructure() {
    Gson gson = new Gson();
    Map<String, Map<String, Object>> ret = new HashMap<>();
    try {
      Type mapType = new TypeToken<Map<String, Map<String, Object>>>() {}.getType();
      ret = gson.fromJson(new FileReader(new File(config.getApplicationDataManager().getStateManager().getLastSentBundleStructure())), mapType);
      if (ret == null) {
        ret = new HashMap<>();
      }
    } catch (JsonSyntaxException e) {
      e.printStackTrace();
    } catch (JsonIOException e) {
      e.printStackTrace();
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    }
    return ret;
  }

  private void writeLastSentBundleStructure(String clientId, Bundle lastSentBundle) {
    Map<String, Map<String, Object>> lastSentBundleStructureMap = this.getLastSentBundleStructure();
    Map<String, Object> structure = BundleUtils.getBundleStructureMap(lastSentBundle);
    lastSentBundleStructureMap.put(clientId, structure);
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    String jsonString = gson.toJson(lastSentBundleStructureMap);
    try (FileWriter writer = new FileWriter(new File(config.getApplicationDataManager().getStateManager().getLastSentBundleStructure()))) {
      writer.write(jsonString);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public void registerSentBundleDetails(String clientId, Bundle sentBundle) {
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
    this.writeLastSentBundleStructure(clientId, sentBundle);
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

  public Optional<Bundle.Builder> getLastSentBundleBuilder(String clientId) {
    Map<String, Map<String, Object>> lastSentBundleStructureMap = this.getLastSentBundleStructure();
    Map<String, Object> structure =
        lastSentBundleStructureMap.getOrDefault(clientId, new HashMap<>());
    return BundleUtils.bundleStructureToBuilder(structure);
  }
}
