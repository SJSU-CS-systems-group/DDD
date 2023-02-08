package com.ddd.client.bundletransmission;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.io.FileUtils;
import com.ddd.client.applicationdatamanager.ApplicationDataManager;
import com.ddd.client.bundlesecurity.BundleSecurity;
import com.ddd.model.ADU;
import com.ddd.model.Acknowledgement;
import com.ddd.model.Bundle;
import com.ddd.utils.AckRecordUtils;
import com.ddd.utils.BundleUtils;
import com.ddd.utils.Constants;

public class BundleTransmission {

  private BundleSecurity bundleSecurity;

  private ApplicationDataManager applicationDataManager;

  /* Bundle generation directory */
  private static final String BUNDLE_GENERATION_DIRECTORY =
      "C:\\Masters\\CS 297-298\\CS 298\\Implementation\\AppStorage\\Client\\BundleTransmission\\bundle-generation";

  private static final String RETRANSMISSION_BUNDLE_DIRECTORY = "retransmission-bundle";

  private static final String TO_BE_BUNDLED_DIRECTORY = "to-be-bundled";

  private static final String TO_SEND_DIRECTORY = "to-send";

  private long BUNDLE_SIZE_LIMIT = 10000;

  public BundleTransmission() {
    this.bundleSecurity = new BundleSecurity();
    this.applicationDataManager = new ApplicationDataManager();
  }

  private String getAckRecordLocation() {
    return BUNDLE_GENERATION_DIRECTORY
        + FileSystems.getDefault().getSeparator()
        + TO_BE_BUNDLED_DIRECTORY
        + FileSystems.getDefault().getSeparator()
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
    builder.setSource(new File(targetDir + FileSystems.getDefault().getSeparator() + bundleId));
    Bundle bundle = builder.build();
    this.bundleSecurity.encryptBundleContents(bundle);
    BundleUtils.writeBundleToFile(bundle, targetDir, bundleId);
    this.applicationDataManager.notifyBundleSent(bundle);
    System.out.println("[BT] Generated new bundle for transmission with bundle id: " + bundleId);
    return bundle;
  }

  private Bundle generateNewBundle(Bundle.Builder builder, File targetDir) {
    String bundleId = this.bundleSecurity.generateNewBundleId();
    builder.setBundleId(bundleId);
    builder.setSource(new File(targetDir + FileSystems.getDefault().getSeparator() + bundleId));
    Bundle bundle = builder.build();
    this.bundleSecurity.encryptBundleContents(bundle);
    BundleUtils.writeBundleToFile(bundle, targetDir, bundleId);
    this.applicationDataManager.notifyBundleSent(bundle);
    System.out.println("[BT] Generated new bundle for transmission with bundle id: " + bundleId);
    return bundle;
  }

  public Bundle generateBundleForTransmission() {
    File retxmnDir =
        new File(
            BUNDLE_GENERATION_DIRECTORY
                + FileSystems.getDefault().getSeparator()
                + RETRANSMISSION_BUNDLE_DIRECTORY);

    File toSendDir =
        new File(
            BUNDLE_GENERATION_DIRECTORY
                + FileSystems.getDefault().getSeparator()
                + TO_SEND_DIRECTORY);

    File[] contents = retxmnDir.listFiles();
    Bundle toSend = null;
    if (contents.length == 0) {
      toSend = this.generateNewBundle(toSendDir);
      this.storeForRetransmission(toSend, retxmnDir);
    } else {
      File retxmnBundleFile = new File(retxmnDir.listFiles()[0].getAbsolutePath());
      Bundle.Builder retxmnBundleBuilder = BundleUtils.readBundleFromFile(retxmnBundleFile);
      Bundle.Builder newBundleBuilder = this.generateBundleBuilder();

      if (BundleUtils.doContentsMatch(newBundleBuilder, retxmnBundleBuilder)) {
        Bundle.Builder builder = new Bundle.Builder();
        builder.setAckRecord(retxmnBundleBuilder.getAckRecord());
        builder.setBundleId(retxmnBundleBuilder.getBundleId());
        builder.setADUs(retxmnBundleBuilder.getADUs());
        builder.setSource(
            new File(
                toSendDir
                    + FileSystems.getDefault().getSeparator()
                    + retxmnBundleBuilder.getBundleId()));

        toSend = builder.build();
        BundleUtils.writeBundleToFile(toSend, toSendDir, toSend.getBundleId());
      } else {
        toSend = this.generateNewBundle(newBundleBuilder, toSendDir);
        this.storeForRetransmission(toSend, retxmnDir);
      }
    }
    return toSend;
  }

  public void deleteSentBundle(Bundle bundle) {
    try {
      FileUtils.deleteDirectory(bundle.getSource());
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
