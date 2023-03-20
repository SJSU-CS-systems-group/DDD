package com.ddd.client.bundlesecurity;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;

import org.apache.commons.lang3.StringUtils;
import org.whispersystems.libsignal.InvalidKeyException;

import com.ddd.model.Bundle;
import com.ddd.model.BundleWrapper;
import com.ddd.model.EncryptedBundle;
import com.ddd.model.EncryptionHeader;
import com.ddd.utils.JarUtils;

public class BundleSecurity {

  static ClientSecurity client = null;
  private static String RootFolder;
  private static String LARGEST_BUNDLE_ID_RECEIVED =
      "/Shared/DB/LARGEST_BUNDLE_ID_RECEIVED.txt";

  private static String BUNDLE_ID_NEXT_COUNTER =
      "/Shared/DB/BUNDLE_ID_NEXT_COUNTER.txt";

  private int counter = 0;

  private String clientId = "client0";

  private String getLargestBundleIdReceived() {
    String bundleId = "";
    try (BufferedReader bufferedReader = new BufferedReader(new FileReader(new File(RootFolder+ LARGEST_BUNDLE_ID_RECEIVED)))){
      String line = "";
      while ((line = bufferedReader.readLine()) != null) {
        bundleId = line.trim();
      }
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
        new BufferedWriter(new FileWriter(new File(RootFolder+ BUNDLE_ID_NEXT_COUNTER)))) {
      bufferedWriter.write(String.valueOf(this.counter));
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public BundleSecurity(String rootFolder) {
    RootFolder = rootFolder;
    String clientKeyPath = RootFolder + "Keys\\Client\\";
    // TODO: Add actual server key path
    String serverKeyPath = RootFolder + "Keys\\Server\\";

    File bundleIdNextCounter = new File(RootFolder+ BUNDLE_ID_NEXT_COUNTER);

    try {
      bundleIdNextCounter.getParentFile().mkdirs();
      bundleIdNextCounter.createNewFile();
    } catch (IOException e) {
      e.printStackTrace();
    }
    File largestBundleIdReceived = new File(RootFolder+ LARGEST_BUNDLE_ID_RECEIVED);

    try {
      largestBundleIdReceived.getParentFile().mkdirs();
      largestBundleIdReceived.createNewFile();
    } catch (IOException e) {
      e.printStackTrace();
    }

    try (BufferedReader bufferedReader = new BufferedReader(new FileReader(bundleIdNextCounter))) {
      String line = bufferedReader.readLine();
      if (StringUtils.isNotEmpty(line)) {
        this.counter = Integer.valueOf(line);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }

    // Initializing
    try {
      client = ClientSecurity.getInstance(1, clientKeyPath, serverKeyPath);
    } catch (NoSuchAlgorithmException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    } catch (InvalidKeyException e) {
      e.printStackTrace();
    }
  }

  public void decryptBundleContents(Bundle bundle) {
    System.out.println("[BS] Decrypting contents of the bundle with id: " + bundle.getBundleId());
  }

  public void registerBundleId(String bundleId) {
    try (BufferedWriter bufferedWriter =
        new BufferedWriter(new FileWriter(new File(RootFolder+ LARGEST_BUNDLE_ID_RECEIVED)))) {
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

  public BundleWrapper wrapBundleContents(Bundle bundle, String bundleWrapperPath) {
    System.out.println("[BS] Encrypting contents of the bundle with id: " + bundle.getBundleId());
    String bundleID = bundle.getBundleId();
    File payloadFile = bundle.getSource(); // Payload JAR
    File bundleWrapperFile = new File(bundleWrapperPath + bundle.getBundleId() + ".jar");
    File unencryptedBundleWrapperParentDir =  bundleWrapperFile.getParentFile(); // TODO read, encrypt and put in a directory
    File unencryptedBundleWrapperDir = new File(unencryptedBundleWrapperParentDir + File.separator + bundleID);

    /* Sign and Encryption */
    String[] signNencryptedPath = new String[0];
    try {
      signNencryptedPath = client.encrypt(payloadFile.getAbsolutePath(), unencryptedBundleWrapperParentDir.getAbsolutePath(),bundleID);
    } catch (IOException e) {
      e.printStackTrace();
    } catch (java.security.InvalidKeyException e) {
      e.printStackTrace();
    } catch (NoSuchAlgorithmException e) {
      e.printStackTrace();
    } catch (SignatureException e) {
      e.printStackTrace();
    } catch (InvalidKeySpecException e) {
      e.printStackTrace();
    } catch (InvalidKeyException e) {
      e.printStackTrace();
    }

    /* Create Headers */
    String[] encHeaderPath = new String[0];
    try {
      encHeaderPath = client.createEncryptionHeader(unencryptedBundleWrapperParentDir.getAbsolutePath(), bundleID);
    } catch (IOException e) {
      e.printStackTrace();
    }
    EncryptionHeader encHeader = new EncryptionHeader(new File(encHeaderPath[0]), new File(encHeaderPath[1]));

    File encryptedPayloadFile = new File(signNencryptedPath[1]);
    // TODO encrypt and Path of encrypted payload
    JarUtils.dirToJar(unencryptedBundleWrapperDir.getAbsolutePath(), bundleWrapperFile.getPath());
    BundleWrapper wrapper = new BundleWrapper (bundle.getBundleId(), encHeader, new EncryptedBundle(encryptedPayloadFile), bundleWrapperFile, new File(signNencryptedPath[0]));
    return wrapper;
  }

  public boolean isLatestReceivedBundleId(String bundleId) {
    String largestBundleIdReceived = this.getLargestBundleIdReceived();
    return (StringUtils.isEmpty(largestBundleIdReceived)
        || this.compareReceivedBundleIds(bundleId, largestBundleIdReceived) > 0);
  }
}
