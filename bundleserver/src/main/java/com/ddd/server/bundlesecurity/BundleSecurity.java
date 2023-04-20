package com.ddd.server.bundlesecurity;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ddd.model.EncryptedPayload;
import com.ddd.model.EncryptionHeader;
import com.ddd.model.Payload;
import com.ddd.model.UncompressedBundle;
import com.ddd.model.UncompressedPayload;
import com.ddd.utils.Constants;

@Service
public class BundleSecurity {

  private static final String BUNDLE_ID_NEXT_COUNTER =
      "C:\\Masters\\CS 297-298\\CS 298\\Implementation\\AppStorage\\Server\\Shared\\DB\\BUNDLE_ID_NEXT_COUNTER.json";

  @Autowired private ServerSecurity serverSecurity;

  private boolean encryptionEnabled = true;

  private Long getRecvdBundleIdCounter(String bundleId) {
    return Long.valueOf(bundleId.split("-")[1]);
  }

  private int compareRecvdBundleIds(String a, String b) {
    return this.getRecvdBundleIdCounter(a).compareTo(this.getRecvdBundleIdCounter(b));
  }

  public BundleSecurity() {
    File bundleIdNextCounter = new File(BUNDLE_ID_NEXT_COUNTER);

    try {
      bundleIdNextCounter.getParentFile().mkdirs();
      bundleIdNextCounter.createNewFile();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public void decryptBundleContents(UncompressedPayload bundle) {
    System.out.println("[BS] Decrypting contents of bundle with id: " + bundle.getBundleId());
  }

  public void processACK(String clientId, String bundleId) {
    // TODO During window implementation
    System.out.println("[BS] Received acknowledgement for sent bundle id " + bundleId);
  }

  public boolean isLatestReceivedBundleId(
      String clientId, String bundleId, String largestBundleIdReceived) {
    return (StringUtils.isEmpty(largestBundleIdReceived)
        || this.compareRecvdBundleIds(bundleId, largestBundleIdReceived) > 0);
  }

  //  public String generateBundleID(String clientKeyPath, boolean direction) {
  //    return "";
  //  }

  public void encryptBundleContents(UncompressedPayload bundle) {
    System.out.println("[BS] Encrypting contents of the bundle with id: " + bundle.getBundleId());
  }

  public boolean isSenderWindowFull(String clientId) {
    // TODO
    return false;
  }

  public String getClientIdFromBundleId(String bundleId) {
    String clientId = "";
    if (bundleId.contains("-")) {
      clientId = bundleId.split("-")[0];
    } else {
      clientId = bundleId.split("#")[0];
    }
    System.out.println(
        "[BS] Client id corresponding to bundle id: " + bundleId + " is " + clientId);
    return clientId;
  }
  /* Compares BundleIDs
   * Paramerters:
   * id1:         First BundleID
   * id2:         Second BundleID
   * direction:   true UPSTREAM (Client->Server), false DOWNSTREAM (Server->Client)
   * Returns:
   * -1 =>  id1 < id2
   * 0  =>  id1 = id2
   * 1  =>  id1 > id2
   */
  public static int compareBundleIDs(String id1, String id2, boolean direction) {
    return -1;
  }

  public void decrypt(String bundlePath, String decryptedPath) {
    System.out.println("mock decrypt implementation");
  }

  public String[] encrypt(String toBeEncPath, String encPath, String bundleID, String clientID) {
    File bundleDir = new File(encPath + File.separator + bundleID);
    bundleDir.mkdirs();

    File bundleIdFile =
        new File(bundleDir + File.separator + Constants.BUNDLE_IDENTIFIER_FILE_NAME);
    try {
      FileUtils.writeLines(bundleIdFile, Arrays.asList(new String[] {bundleID}));
    } catch (IOException e1) {
      e1.printStackTrace();
    }
    try {
      FileUtils.copyFile(
          new File(toBeEncPath), new File(bundleDir + File.separator + bundleID + ".jar"));
    } catch (IOException e) {
      e.printStackTrace();
    }

    System.out.println("mock encrypt implementation");
    return null;
  }

  public Payload decryptPayload(UncompressedBundle uncompressedBundle) {
    String bundleId = "";
    File decryptedPayloadJar =
        new File(
            uncompressedBundle.getSource().getAbsolutePath()
                + File.separator
                + Constants.BUNDLE_ENCRYPTED_PAYLOAD_FILE_NAME
                + ".jar");

    if (this.encryptionEnabled) {
      try {
        this.serverSecurity.decrypt(
            uncompressedBundle.getSource().getAbsolutePath(),
            uncompressedBundle.getSource().getAbsolutePath());
      } catch (Exception e) {
        // TODO
        e.printStackTrace();
      }
      
      try {
        bundleId =
            this.serverSecurity.getBundleIDFromFile(
                uncompressedBundle.getSource().getAbsolutePath());
      } catch (Exception e) {
        e.printStackTrace();
      }
      File decryptedPayload =
          new File(
              uncompressedBundle.getSource().getAbsolutePath()
                  + File.separator
                  + bundleId
                  + ".decrypted");
      if (decryptedPayload.exists()) {
        decryptedPayload.renameTo(decryptedPayloadJar);
      }
    }
    return new Payload(bundleId, decryptedPayloadJar);
  }

  public UncompressedBundle encryptPayload(String clientId, Payload payload, String bundleGenDirPath) {
    String bundleId = payload.getBundleId();
    String[] paths;
    if (!this.encryptionEnabled) {
      return new UncompressedBundle(bundleId, payload.getSource(), null, null, null);
    }
    try {
      paths =
          this.serverSecurity.encrypt(
              payload.getSource().getAbsolutePath(),
              bundleGenDirPath,
              bundleId,
              clientId);

      EncryptedPayload encryptedPayload = new EncryptedPayload(bundleId, new File(paths[0]));

      File source = new File(bundleGenDirPath + File.separator + bundleId);
      EncryptionHeader encHeader =
          new EncryptionHeader(new File(paths[2]), new File(paths[3]), new File(paths[4]));
      return new UncompressedBundle( // TODO get encryption header, payload signature
          bundleId, source, encHeader, encryptedPayload, new File(paths[1]));

    } catch (Exception e) {
      e.printStackTrace();
    }
    return null;
  }
}
