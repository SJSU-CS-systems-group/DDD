package com.ddd.client.bundlesecurity;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import org.apache.commons.lang3.StringUtils;
import org.whispersystems.libsignal.NoSessionException;
import com.ddd.bundleclient.HelloworldActivity;
import com.ddd.model.EncryptedPayload;
import com.ddd.model.EncryptionHeader;
import com.ddd.model.Payload;
import com.ddd.model.UncompressedBundle;
import com.ddd.model.UncompressedPayload;
import com.ddd.utils.Constants;
import com.ddd.utils.JarUtils;
import android.util.Log;

public class BundleSecurity {

  static ClientSecurity client = null;
  private static String RootFolder;
  private static String LARGEST_BUNDLE_ID_RECEIVED =
      "/Shared/DB/LARGEST_BUNDLE_ID_RECEIVED.txt";

  private static String BUNDLE_ID_NEXT_COUNTER =
      "/Shared/DB/BUNDLE_ID_NEXT_COUNTER.txt";

  private int counter = 0;

  private BundleIDGenerator bundleIDGenerator;

  private String clientId = "client0";

  private String clientKeyPath = "";
  private String serverKeyPath = "";
  
  private boolean isEncryptionEnabled = true;

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
    // TODO: Add actual server key path
    clientKeyPath = RootFolder + "/Keys/Client/";
    serverKeyPath = RootFolder + "/Keys/Server/";

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

    /* Initializing Security Module*/
    try {
      client = ClientSecurity.getInstance(1, clientKeyPath, serverKeyPath);
      bundleIDGenerator = BundleIDGenerator.getInstance();
      Log.d(HelloworldActivity.TAG,"Kuch Bhi");
    } catch (NoSuchAlgorithmException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    } catch (NoSessionException e) {
      e.printStackTrace();
    } catch (org.whispersystems.libsignal.InvalidKeyException e) {
      e.printStackTrace();
    }
  }

  public String getClientKeyPath() {
    return clientKeyPath;
  }


  public String getServerKeyPath() {
    return serverKeyPath;
  }
  public void decryptBundleContents(UncompressedPayload bundle) {
    System.out.println("[BS] Decrypting contents of the bundle with id: " + bundle.getBundleId());
  }


  public String generateNewBundleId() {
    return bundleIDGenerator.generateBundleID(clientKeyPath, BundleIDGenerator.UPSTREAM);
  }

  public UncompressedBundle encryptPayload(Payload payload, String bundleGenDirPath) {
    String bundleId = payload.getBundleId();
    System.out.println("[BS] Payload source:" + payload.getSource() + " bundle id " + bundleId);
    String[] paths;
    if (!this.isEncryptionEnabled) {
      return new UncompressedBundle( 
          bundleId, payload.getSource(), null, null, null);
    }
    try {
       paths = client.encrypt(
            payload.getSource().getAbsolutePath(),
            bundleGenDirPath,
            bundleId);
  
  
        EncryptedPayload encryptedPayload =
            new EncryptedPayload(bundleId, new File(paths[0]));
    
        File source = new File(bundleGenDirPath + File.separator + bundleId);
        EncryptionHeader encHeader = new EncryptionHeader(new File(paths[2]), new File(paths[3]));
        return new UncompressedBundle( 
            bundleId, source, encHeader, encryptedPayload, new File(paths[1]));
    } catch (Exception e) {
      e.printStackTrace();
    }
    return null;
  }
  public Payload decryptPayload(UncompressedBundle uncompressedBundle) {
    File decryptedPayloadJar =
        new File(
            uncompressedBundle.getSource().getAbsolutePath()
                + File.separator
                + Constants.BUNDLE_ENCRYPTED_PAYLOAD_FILE_NAME
                + ".jar");

    if (this.isEncryptionEnabled) {
      
      try {
        ClientSecurity clientSecurity = ClientSecurity.getInstance(counter, clientKeyPath, serverKeyPath);
        clientSecurity.decrypt(
            uncompressedBundle.getSource().getAbsolutePath(),
            uncompressedBundle.getSource().getAbsolutePath());

        String clientId = SecurityUtils.getClientID(uncompressedBundle.getSource().getAbsolutePath());
        String bundleId = clientSecurity.getBundleIDFromFile(uncompressedBundle.getSource().getAbsolutePath());

        File decryptedPayload =
            new File(
                uncompressedBundle.getSource().getAbsolutePath()
                    + File.separator
                    + bundleId
                    + ".decrypted");
        if (decryptedPayload.exists()) {
          decryptedPayload.renameTo(decryptedPayloadJar);
        }
      } catch (Exception e) {
        // TODO
        e.printStackTrace();
      }
    }

    return new Payload(uncompressedBundle.getBundleId(), decryptedPayloadJar);
  }

  public boolean isLatestReceivedBundleId(String bundleId) {
    String largestBundleIdReceived = this.getLargestBundleIdReceived();
    return (StringUtils.isEmpty(largestBundleIdReceived)
        || this.compareReceivedBundleIds(bundleId, largestBundleIdReceived) > 0);
  }
}
