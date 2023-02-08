package com.ddd.client.applicationdatamanager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.ddd.model.ADU;
import com.ddd.model.Bundle;
import com.ddd.utils.ADUUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

class StateManager {

  private DataStoreAdaptor dataStoreAdaptor;

  /* Database tables */
  private static final String SENT_BUNDLE_DETAILS =
      "C:\\Masters\\CS 297-298\\CS 298\\Implementation\\AppStorage\\Client\\Shared\\DB\\SENT_BUNDLE_DETAILS.json";

  private static final String LARGEST_ADU_ID_RECEIVED =
      "C:\\Masters\\CS 297-298\\CS 298\\Implementation\\AppStorage\\Client\\Shared\\DB\\LARGEST_ADU_ID_RECEIVED.json";

  private static final String LARGEST_ADU_ID_DELIVERED =
      "C:\\Masters\\CS 297-298\\CS 298\\Implementation\\AppStorage\\Client\\Shared\\DB\\LARGEST_ADU_ID_DELIVERED.json";

  public StateManager() {
    this.dataStoreAdaptor = new DataStoreAdaptor();
  }

  /* Largest ADU ID received */

  private Map<String, Long> getLargestADUIdReceivedDetails() {
    Gson gson = new Gson();
    Map<String, Long> ret = null;
    try {
      Type mapType = new TypeToken<Map<String, Long>>() {}.getType();
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

  private void writeLargestADUIdReceivedDetails(Map<String, Long> largestADUIdReceivedDetails) {
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    String jsonString = gson.toJson(largestADUIdReceivedDetails);
    try (FileWriter writer = new FileWriter(new File(LARGEST_ADU_ID_RECEIVED))) {
      writer.write(jsonString);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public Long getLargestADUIdReceived(String appId) {
    Map<String, Long> receivedADUIds = this.getLargestADUIdReceivedDetails();
    return receivedADUIds.get(appId);
  }

  public void updateLargestADUIdReceived(String appId, Long aduId) {
    Map<String, Long> largestADUIdReceivedDetails = this.getLargestADUIdReceivedDetails();
    largestADUIdReceivedDetails.put(appId, aduId);
    this.writeLargestADUIdReceivedDetails(largestADUIdReceivedDetails);
  }

  /* Largest ADU ID Delivered Details*/
  private void writeLargestADUIdDeliveredDetails(Map<String, Long> largestADUIdDeliveredDetails) {
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    String jsonString = gson.toJson(largestADUIdDeliveredDetails);
    try (FileWriter writer = new FileWriter(new File(LARGEST_ADU_ID_DELIVERED))) {
      writer.write(jsonString);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private Map<String, Long> getLargestADUIdDeliveredDetails() {
    Gson gson = new Gson();
    Map<String, Long> ret = null;
    try {
      Type mapType = new TypeToken<Map<String, Long>>() {}.getType();
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

  public Long getLargestADUIdDeliveredByAppId(String appId) {
    return this.getLargestADUIdDeliveredDetails().get(appId);
  }

  /* Sent bundle Details*/

  private Map<String, Map<String, Long>> getSentBundleDetails() {
    Gson gson = new Gson();
    Map<String, Map<String, Long>> ret = null;
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

  public void processAcknowledgement(String bundleId) {
    System.out.println("[ADM-SM] Registering acknowledgement for sent bundle id: " + bundleId);
    Map<String, Map<String, Long>> sentBundleDetails = this.getSentBundleDetails();
    Map<String, Long> details = sentBundleDetails.getOrDefault(bundleId, new HashMap<>());
    Map<String, Long> largestADUIdDeliveredDetails = this.getLargestADUIdDeliveredDetails();
    for (String appId : details.keySet()) {
      Long aduId = details.get(appId);
      this.dataStoreAdaptor.deleteADUs(appId, aduId);
      if (!largestADUIdDeliveredDetails.containsKey(appId)
          || aduId > largestADUIdDeliveredDetails.get(appId)) {
        largestADUIdDeliveredDetails.put(appId, aduId);
      }
    }
    this.writeLargestADUIdDeliveredDetails(largestADUIdDeliveredDetails);
  }
}

class DataStoreAdaptor {

  private static final String ADU_LOCATION =
      "C:\\Masters\\CS 297-298\\CS 298\\Implementation\\AppStorage\\Client\\ApplicationDataManager\\ADU";

  private static final String SEND_SUBDIR = "send";

  private static final String RECV_SUBDIR = "receive";

  public void persistADU(ADU adu) {
    File receiveDir =
        new File(
            ADU_LOCATION
                + FileSystems.getDefault().getSeparator()
                + RECV_SUBDIR
                + FileSystems.getDefault().getSeparator()
                + adu.getAppId());
    if (!receiveDir.exists()) {
      receiveDir.mkdirs();
    }
    ADUUtils.writeADU(adu, receiveDir);
    System.out.println(
        "[ADM-DSA] Persisting inbound ADU "
            + adu.getAppId()
            + "-"
            + adu.getADUId()
            + " to the Data Store");
  }

  public void deleteADUs(String appId, long aduIdEnd) {
    File aduSendDir =
        new File(
            ADU_LOCATION
                + FileSystems.getDefault().getSeparator()
                + SEND_SUBDIR
                + FileSystems.getDefault().getSeparator()
                + appId);

    for (final File appDir : aduSendDir.listFiles()) {
      String currAppId = appDir.getName();
      if (!currAppId.equals(appId)) {
        continue;
      }
      for (final File aduFile : appDir.listFiles()) {
        ADU adu = ADUUtils.readADUFromFile(aduFile, currAppId);
        if (adu.getADUId() <= aduIdEnd) {
          adu.getSource().delete();
        }
      }
    }
    System.out.println("[DSA] Deleted ADUs for application " + appId + " with id upto " + aduIdEnd);
    System.out.println(
        "[ADM-DSA] Deleting outbound ADUs of application " + appId + " upto id " + aduIdEnd);
  }

  private ADU fetchADU(String appId, long aduId) {
    File aduDirectory =
        new File(
            ADU_LOCATION
                + FileSystems.getDefault().getSeparator()
                + SEND_SUBDIR
                + FileSystems.getDefault().getSeparator()
                + appId);
    System.out.println(
        "[ADM-DSA] Fetching ADU id = "
            + aduId
            + " of application: "
            + appId
            + " from "
            + aduDirectory.getAbsolutePath());
    if (!aduDirectory.exists() || aduDirectory.listFiles().length == 0) {
      return null;
    }

    ADU ret = null;
    for (ADU adu : ADUUtils.readADUs(aduDirectory, appId)) {
      if (adu.getADUId() == aduId) {
        ret = adu;
      }
    }
    return ret;
  }

  public List<ADU> fetchADUs(String appId, long aduIdStart) {
    ADU adu = null;
    List<ADU> ret = new ArrayList<>();
    long aduId = aduIdStart;
    while ((adu = this.fetchADU(appId, aduId)) != null) {
      ret.add(adu);
      aduId++;
    }
    return ret;
  }
}

public class ApplicationDataManager {

  private StateManager stateManager;

  private DataStoreAdaptor dataStoreAdaptor;

  private Long APP_DATA_SIZE_LIMIT = 10000L;

  private static final String REGISTERED_APP_IDS =
      "C:\\Masters\\CS 297-298\\CS 298\\Implementation\\AppStorage\\Client\\Shared\\DB\\REGISTERED_APP_IDS.txt";

  public ApplicationDataManager() {
    this.stateManager = new StateManager();
    this.dataStoreAdaptor = new DataStoreAdaptor();
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

  public void registerAppId(String appId) {
    try (BufferedWriter bufferedWriter =
        new BufferedWriter(new FileWriter(new File(REGISTERED_APP_IDS)))) {
      bufferedWriter.append(appId);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public void processAcknowledgement(String bundleId) {
    System.out.println("[ADM] Processing acknowledgement for sent bundle id: " + bundleId);
    if ("HB".equals(bundleId)) {
      System.out.println("[ADM] This is a heartbeat message.");
      return;
    }
    this.stateManager.processAcknowledgement(bundleId);
  }

  public void storeADUs(List<ADU> adus) {
    System.out.println("[ADM] Storing ADUs in the Data Store");

    for (final ADU adu : adus) {
      Long largestAduIdReceived = this.stateManager.getLargestADUIdReceived(adu.getAppId());
      System.out.println(
          "[ADM] Largest id for ADUs received for application "
              + adu.getAppId()
              + " is "
              + largestAduIdReceived);
      if (largestAduIdReceived != null && adu.getADUId() <= largestAduIdReceived) {
        continue;
      }
      this.stateManager.updateLargestADUIdReceived(adu.getAppId(), adu.getADUId());
      this.dataStoreAdaptor.persistADU(adu);
    }
  }

  public List<ADU> fetchADUs() {
    List<ADU> res = new ArrayList<>();
    for (String appId : this.getRegisteredAppIds()) {
      Long largestAduIdDelivered = this.stateManager.getLargestADUIdDeliveredByAppId(appId);
      Long aduIdStart = (largestAduIdDelivered != null) ? (largestAduIdDelivered + 1) : 1;
      List<ADU> adus = this.dataStoreAdaptor.fetchADUs(appId, aduIdStart);
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

  public void notifyBundleSent(Bundle bundle) {
    this.stateManager.registerSentBundleDetails(bundle);
  }
}
