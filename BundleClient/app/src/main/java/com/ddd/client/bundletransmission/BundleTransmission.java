package com.ddd.client.bundletransmission;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.ddd.client.applicationdatamanager.ApplicationDataManager;
import com.ddd.client.bundlesecurity.BundleSecurity;
import com.ddd.model.ADU;
import com.ddd.model.Acknowledgement;
import com.ddd.model.Bundle;
import com.ddd.utils.AckRecordUtils;
import com.ddd.utils.BundleUtils;
import com.ddd.utils.Constants;

import org.apache.commons.io.FileUtils;

public class BundleTransmission {

  private BundleSecurity bundleSecurity;

  private ApplicationDataManager applicationDataManager;

  private String RootDirectory;

  /* Bundle generation directory */
  private static String BUNDLE_GENERATION_DIRECTORY =
      "/BundleTransmission/bundle-generation";

  private static final String RETRANSMISSION_BUNDLE_DIRECTORY = "retransmission-bundle";

  private static final String TO_BE_BUNDLED_DIRECTORY = "to-be-bundled";

  private static final String TO_SEND_DIRECTORY = "to-send";

  private long BUNDLE_SIZE_LIMIT = 1000000000;

  private String ROOT_DIR = "";
  public BundleTransmission(String rootFolder) {
    ROOT_DIR = rootFolder;
    this.bundleSecurity = new BundleSecurity(ROOT_DIR);
    this.applicationDataManager = new ApplicationDataManager(ROOT_DIR);
    try {
      File bundleGenerationDir = new File(ROOT_DIR+BUNDLE_GENERATION_DIRECTORY);
      bundleGenerationDir.mkdirs();
      File toBeBundledDir =
              new File(bundleGenerationDir + File.separator + TO_BE_BUNDLED_DIRECTORY);
      toBeBundledDir.mkdirs();
      File ackRecFile =
              new File(toBeBundledDir + File.separator + Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME);
      ackRecFile.createNewFile();

      FileUtils.writeLines(ackRecFile, Arrays.asList(new String[] {"HB"}));

      File tosendDir = new File(bundleGenerationDir + File.separator + TO_SEND_DIRECTORY);
      tosendDir.mkdirs();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private String getAckRecordLocation() {
    return ROOT_DIR + BUNDLE_GENERATION_DIRECTORY
        + "/"
        + TO_BE_BUNDLED_DIRECTORY
        + "/"
        + Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME;
  }

  public void processReceivedBundles(String bundlesLocation) {
    File bundleStorageDirectory = new File(bundlesLocation);

    for (final File bundleFile : bundleStorageDirectory.listFiles()) {
      Bundle bundle = BundleUtils.readBundleFromFile(bundleFile).build();
      if (!this.bundleSecurity.isLatestReceivedBundleId(bundle.getBundleId())) {
        continue;
      }
      AckRecordUtils.writeAckRecordToFile(
          new Acknowledgement(bundle.getBundleId()), new File(this.getAckRecordLocation()));
      this.bundleSecurity.decryptBundleContents(bundle);
      this.bundleSecurity.registerBundleId(bundle.getBundleId());
      this.applicationDataManager.processAcknowledgement(bundle.getAckRecord().getBundleId());
      this.applicationDataManager.storeADUs(bundle.getADUs());
      try {
        FileUtils.deleteDirectory(bundle.getSource());
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  private Bundle.Builder generateBundleBuilder() {
    List<ADU> ADUs = this.applicationDataManager.fetchADUs();
    Bundle.Builder builder = new Bundle.Builder();
    Acknowledgement ackRecord =
        AckRecordUtils.readAckRecordFromFile(new File(this.getAckRecordLocation()));
    builder.setAckRecord(ackRecord);

    long totalSize = ackRecord.getSize();
    List<ADU> adusToPack = new ArrayList<>();
    for (ADU adu : ADUs) {
      if (adu.getSize() + totalSize > this.BUNDLE_SIZE_LIMIT) {
        break;
      }
      totalSize += adu.getSize();
      adusToPack.add(adu);
    }
    builder.setADUs(adusToPack);

    return builder;
  }

  private void storeForRetransmission(Bundle bundle, File targetDirectory) {
    for (final File bundleFile : targetDirectory.listFiles()) {
      try {
        FileUtils.deleteDirectory(bundleFile);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    BundleUtils.writeBundleToFile(bundle, targetDirectory, bundle.getBundleId());
  }

  private Bundle generateNewBundle(File targetDir) {
    Bundle.Builder builder = this.generateBundleBuilder();
    String bundleId = this.bundleSecurity.generateNewBundleId();
    builder.setBundleId(bundleId);
    builder.setSource(new File(targetDir + "/" + bundleId + ".jar"));
    Bundle bundle = builder.build();
    this.bundleSecurity.encryptBundleContents(bundle);
    BundleUtils.writeBundleToFile(bundle, targetDir, bundleId);
    this.applicationDataManager.notifyBundleSent(bundle);
    System.out.println("[BT] Generated new bundle for transmission with bundle id: " + bundleId);
    return bundle;
  }

  private Bundle generateNewBundle(Bundle.Builder builder, File targetDir, String bundleId) {
    builder.setBundleId(bundleId);
    builder.setSource(new File(targetDir + "/" + bundleId + ".jar"));
    Bundle bundle = builder.build();
    this.bundleSecurity.encryptBundleContents(bundle);
    BundleUtils.writeBundleToFile(bundle, targetDir, bundleId);
    this.applicationDataManager.notifyBundleSent(bundle);
    System.out.println("[BT] Generated new bundle for transmission with bundle id: " + bundleId);
    return bundle;
  }

  public Bundle generateBundleForTransmission() {
    File toSendDir =
            new File(
                    ROOT_DIR + BUNDLE_GENERATION_DIRECTORY
                            + File.separator
                            + TO_SEND_DIRECTORY);

    Bundle toSend = null;
    Optional<Bundle.Builder> optional = this.applicationDataManager.getLastSentBundleBuilder();
    if (!optional.isPresent()) {
      toSend = this.generateNewBundle(toSendDir);
    } else {
      Bundle.Builder lastSentBundleBuilder = optional.get();
      Bundle.Builder newBundleBuilder = this.generateBundleBuilder();

      String bundleId = "";
      if (BundleUtils.doContentsMatch(newBundleBuilder, lastSentBundleBuilder)) {
        bundleId = lastSentBundleBuilder.getBundleId();
      } else {
        bundleId = this.bundleSecurity.generateNewBundleId();
      }
      toSend = this.generateNewBundle(newBundleBuilder, toSendDir, bundleId);
    }
    return toSend;
  }


  public void notifyBundleSent(Bundle bundle) {
    // TODO
    FileUtils.deleteQuietly(bundle.getSource());
  }
}
