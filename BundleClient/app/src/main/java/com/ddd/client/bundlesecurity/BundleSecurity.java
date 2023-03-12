package com.ddd.client.bundlesecurity;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.apache.commons.lang3.StringUtils;
import com.ddd.model.Bundle;

public class BundleSecurity {

  private static final String LARGEST_BUNDLE_ID_RECEIVED =
      "C:\\Masters\\CS 297-298\\CS 298\\Implementation\\AppStorage\\Client\\Shared\\DB\\LARGEST_BUNDLE_ID_RECEIVED.txt";

  private static final String BUNDLE_ID_NEXT_COUNTER =
      "C:\\Masters\\CS 297-298\\CS 298\\Implementation\\AppStorage\\Client\\Shared\\DB\\BUNDLE_ID_NEXT_COUNTER.txt";

  private int counter = 0;

  private String clientId = "client0";

  private String getLargestBundleIdReceived() {
    String bundleId = "";
    try {
      bundleId = Files.readString(Paths.get(LARGEST_BUNDLE_ID_RECEIVED));
    } catch (IOException e) {
      e.printStackTrace();
    }
    System.out.println("[BS] Largest bundle id received so far: " + bundleId);
    return bundleId.trim();
  }

  private Long getRecvdBundleIdCounter(String bundleId) {
    return Long.valueOf(bundleId.split("#")[1]);
  }

  private int compareReceivedBundleIds(String a, String b) {
    return this.getRecvdBundleIdCounter(a).compareTo(this.getRecvdBundleIdCounter(b));
  }

  private void writeCounterToDB() {
    try (BufferedWriter bufferedWriter =
        new BufferedWriter(new FileWriter(new File(BUNDLE_ID_NEXT_COUNTER)))) {
      bufferedWriter.write(String.valueOf(this.counter));
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public BundleSecurity() {
    try (BufferedReader bufferedReader =
        new BufferedReader(new FileReader(new File(BUNDLE_ID_NEXT_COUNTER)))) {
      this.counter = Integer.valueOf(bufferedReader.readLine());
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public void decryptBundleContents(Bundle bundle) {
    System.out.println("[BS] Decrypting contents of the bundle with id: " + bundle.getBundleId());
  }

  public void registerBundleId(String bundleId) {
    try (BufferedWriter bufferedWriter =
        new BufferedWriter(new FileWriter(new File(LARGEST_BUNDLE_ID_RECEIVED)))) {
      bufferedWriter.write(bundleId);
    } catch (IOException e) {
      e.printStackTrace();
    }
    System.out.println("[BS] Registered bundle identifier: " + bundleId);
  }

  public String generateNewBundleId() {
    String counterString = Integer.valueOf(this.counter).toString();
    this.counter++;
    this.writeCounterToDB();
    return this.clientId + "-" + counterString;
  }

  public void encryptBundleContents(Bundle bundle) {
    System.out.println("[BS] Encrypting contents of the bundle with id: " + bundle.getBundleId());
  }

  public boolean isLatestReceivedBundleId(String bundleId) {
    String largestBundleIdReceived = this.getLargestBundleIdReceived();
    return (StringUtils.isEmpty(largestBundleIdReceived)
        || this.compareReceivedBundleIds(bundleId, largestBundleIdReceived) > 0);
  }
}
