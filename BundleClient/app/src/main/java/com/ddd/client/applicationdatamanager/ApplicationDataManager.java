package com.ddd.client.applicationdatamanager;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

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
import java.util.Optional;

import com.ddd.datastore.providers.MessageProvider;
import com.ddd.model.ADU;
import com.ddd.model.Bundle;
import com.ddd.utils.ADUUtils;
import com.ddd.utils.BundleUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

class StateManager {

  private DataStoreAdaptor dataStoreAdaptor;

  /* Database tables */
  private static String SENT_BUNDLE_DETAILS =
      "/Shared/DB/SENT_BUNDLE_DETAILS.json";

  private static String LARGEST_ADU_ID_RECEIVED =
      "/Shared/DB/LARGEST_ADU_ID_RECEIVED.json";

  private static String LARGEST_ADU_ID_DELIVERED =
      "/Shared/DB/LARGEST_ADU_ID_DELIVERED.json";

  private static String LAST_SENT_BUNDLE_STRUCTURE =
          "/Shared/DB/LAST_SENT_BUNDLE_STRUCTURE.json";

  private String ROOT_DIR = "";
  public StateManager(String rootFolder) {
    ROOT_DIR = rootFolder;
    this.dataStoreAdaptor = new DataStoreAdaptor(rootFolder);
    try {
      File sentBundleDetails = new File(ROOT_DIR+SENT_BUNDLE_DETAILS);
      sentBundleDetails.getParentFile().mkdirs();
      sentBundleDetails.createNewFile();
      File largestADUIdReceived = new File(ROOT_DIR+LARGEST_ADU_ID_DELIVERED);
      largestADUIdReceived.getParentFile().mkdirs();
      largestADUIdReceived.createNewFile();
      File largestADUIdDelivered = new File(ROOT_DIR+LARGEST_ADU_ID_RECEIVED);
      largestADUIdDelivered.getParentFile().mkdirs();
      largestADUIdDelivered.createNewFile();
      File lastSentBundleStructure = new File(ROOT_DIR+LAST_SENT_BUNDLE_STRUCTURE);
      lastSentBundleStructure.getParentFile().mkdirs();
      lastSentBundleStructure.createNewFile();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /* Largest ADU ID received */

  private Map<String, Long> getLargestADUIdReceivedDetails() {
    Gson gson = new Gson();
    Map<String, Long> ret = null;
    try {
      Type mapType = new TypeToken<Map<String, Long>>() {}.getType();
      ret = gson.fromJson(new FileReader(ROOT_DIR+LARGEST_ADU_ID_RECEIVED), mapType);
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

  private void writeLargestADUIdReceivedDetails(Map<String, Long> largestADUIdReceivedDetails) {
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    String jsonString = gson.toJson(largestADUIdReceivedDetails);
    try (FileWriter writer = new FileWriter(new File(ROOT_DIR+LARGEST_ADU_ID_RECEIVED))) {
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
    try (FileWriter writer = new FileWriter(new File(ROOT_DIR+LARGEST_ADU_ID_DELIVERED))) {
      writer.write(jsonString);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private Map<String, Long> getLargestADUIdDeliveredDetails() {
    Gson gson = new Gson();
    Map<String, Long> ret = new HashMap<>();
    try {
      Type mapType = new TypeToken<Map<String, Long>>() {}.getType();
      ret = gson.fromJson(new FileReader(ROOT_DIR+LARGEST_ADU_ID_DELIVERED), mapType);
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

  public Long getLargestADUIdDeliveredByAppId(String appId) {
    return this.getLargestADUIdDeliveredDetails().get(appId);
  }

  /* Sent bundle Details*/

  private Map<String, Map<String, Long>> getSentBundleDetails() {
    Gson gson = new Gson();
    Map<String, Map<String, Long>> ret = new HashMap<>();
    try {
      Type mapType = new TypeToken<Map<String, Map<String, Long>>>() {}.getType();
      ret = gson.fromJson(new FileReader(new File(ROOT_DIR+SENT_BUNDLE_DETAILS)), mapType);
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
    try (FileWriter writer = new FileWriter(new File(ROOT_DIR+SENT_BUNDLE_DETAILS))) {
      writer.write(jsonString);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void writeLastSentBundleStructure(Bundle lastSentBundle) {
    BundleUtils.writeBundleStructureToJson(lastSentBundle, new File(ROOT_DIR+LAST_SENT_BUNDLE_STRUCTURE));
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
    this.writeLastSentBundleStructure(sentBundle);
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

  public Optional<Bundle.Builder> getLastSentBundleBuilder() {
    return BundleUtils.jsonToBundleBuilder(new File(ROOT_DIR+LAST_SENT_BUNDLE_STRUCTURE));
  }
}

public class ApplicationDataManager {

  private StateManager stateManager;

  private DataStoreAdaptor dataStoreAdaptor;

  private Long APP_DATA_SIZE_LIMIT = 1000000000L;

  private static String REGISTERED_APP_IDS =
      "/Shared/REGISTERED_APP_IDS.txt";

  private String ROOT_DIR = "";
  public ApplicationDataManager(String rootDir) {
    ROOT_DIR = rootDir;
    this.stateManager = new StateManager(rootDir);
    this.dataStoreAdaptor = new DataStoreAdaptor(rootDir);
  }

  public List<String> getRegisteredAppIds() {
    List<String> registeredAppIds = new ArrayList<>();
    try (BufferedReader bufferedReader =
        new BufferedReader(new FileReader(new File(ROOT_DIR + REGISTERED_APP_IDS)))) {
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
        new BufferedWriter(new FileWriter(new File(ROOT_DIR + REGISTERED_APP_IDS)))) {
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
  public Optional<Bundle.Builder> getLastSentBundleBuilder() {
    return this.stateManager.getLastSentBundleBuilder();
  }
}
