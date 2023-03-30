package com.ddd.server.bundlesecurity;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import com.ddd.model.Bundle;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

public class BundleSecurity {

  private static final String LARGEST_BUNDLE_ID_RECEIVED =
      "C:\\Masters\\CS 297-298\\CS 298\\Implementation\\AppStorage\\Server\\Shared\\DB\\LARGEST_BUNDLE_ID_RECEIVED.json";

  private static final String BUNDLE_ID_NEXT_COUNTER =
      "C:\\Masters\\CS 297-298\\CS 298\\Implementation\\AppStorage\\Server\\Shared\\DB\\BUNDLE_ID_NEXT_COUNTER.json";

  private Long getRecvdBundleIdCounter(String bundleId) {
    return Long.valueOf(bundleId.split("-")[1]);
  }

  private int compareRecvdBundleIds(String a, String b) {
    return this.getRecvdBundleIdCounter(a).compareTo(this.getRecvdBundleIdCounter(b));
  }

  private String getLargestBundleIdReceived(String clientId) {
    return this.getLargestReceivedBundleIdDetails().getOrDefault(clientId, "");
  }

  private Map<String, String> getLargestReceivedBundleIdDetails() {
    Gson gson = new Gson();
    Map<String, String> ret = new HashMap<>();
    try {
      Type mapType = new TypeToken<Map<String, String>>() {}.getType();
      ret = gson.fromJson(new FileReader(LARGEST_BUNDLE_ID_RECEIVED), mapType);
    } catch (JsonSyntaxException e) {
      e.printStackTrace();
    } catch (JsonIOException e) {
      e.printStackTrace();
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    }
    return ret;
  }

  private void writeLargestBundleIdDetails(Map<String, String> largestReceivedBundleIdDetails) {
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    String jsonString = gson.toJson(largestReceivedBundleIdDetails);
    try (FileWriter writer = new FileWriter(new File(LARGEST_BUNDLE_ID_RECEIVED))) {
      writer.write(jsonString);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public void registerRecvdBundleId(String bundleId) {
    Map<String, String> largestReceivedBundleIdDetails = this.getLargestReceivedBundleIdDetails();
    largestReceivedBundleIdDetails.put(this.getClientIdFromRecvdBundleId(bundleId), bundleId);
    this.writeLargestBundleIdDetails(largestReceivedBundleIdDetails);
    System.out.println("[BS] Registered bundle identifier: " + bundleId);
  }

  private Map<String, Long> getBundleIdNextCounters() {
    Gson gson = new Gson();
    Map<String, Long> ret = new HashMap<>();
    try {
      Type mapType = new TypeToken<Map<String, Long>>() {}.getType();
      ret = gson.fromJson(new FileReader(BUNDLE_ID_NEXT_COUNTER), mapType);
    } catch (JsonSyntaxException e) {
      e.printStackTrace();
    } catch (JsonIOException e) {
      e.printStackTrace();
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    }
    return ret;
  }

  private void writeBundleIdNextCounters(Map<String, Long> counters) {
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    String jsonString = gson.toJson(counters);
    try (FileWriter writer = new FileWriter(new File(BUNDLE_ID_NEXT_COUNTER))) {
      writer.write(jsonString);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void writeCounterToDB(String clientId, Long counter) {
    Map<String, Long> nextBundleIdCounters = this.getBundleIdNextCounters();
    nextBundleIdCounters.put(clientId, counter);
    this.writeBundleIdNextCounters(nextBundleIdCounters);
  }

  public String getClientIdFromRecvdBundleId(String bundleId) {
    String clientId = bundleId.split("-")[0];
    System.out.println(
        "[BS] Client id corresponding to bundle id: " + bundleId + " is " + clientId);
    return clientId;
  }

  public void decryptBundleContents(Bundle bundle) {
    System.out.println("[BS] Decrypting contents of bundle with id: " + bundle.getBundleId());
  }

  public void registerAck(String bundleId) {
    // TODO During window implementation
    System.out.println("[BS] Received acknowledgement for sent bundle id " + bundleId);
  }

  public boolean isLatestReceivedBundleId(String clientId, String bundleId) {
    String largestBundleIdReceived = this.getLargestBundleIdReceived(clientId);
    return (StringUtils.isEmpty(largestBundleIdReceived)
        || this.compareRecvdBundleIds(bundleId, largestBundleIdReceived) > 0);
  }

  public String generateBundleId(String clientId) {
    Long counter = 0L;
    if (counter != null) {
      counter = this.getBundleIdNextCounters().get(clientId);
    }
    this.writeCounterToDB(clientId, counter + 1);
    return clientId + "#" + counter.toString();
  }

  public void encryptBundleContents(Bundle bundle) {
    System.out.println("[BS] Encrypting contents of the bundle with id: " + bundle.getBundleId());
  }

  public boolean isSenderWindowFull(String clientId) {
    // TODO
    return false;
  }
}