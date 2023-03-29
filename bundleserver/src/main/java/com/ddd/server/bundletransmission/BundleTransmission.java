package com.ddd.server.bundletransmission;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.io.FileUtils;
import com.ddd.model.ADU;
import com.ddd.model.Acknowledgement;
import com.ddd.model.Bundle;
import com.ddd.model.BundleTransferDTO;
import com.ddd.server.applicationdatamanager.ApplicationDataManager;
import com.ddd.server.bundlerouting.BundleRouting;
import com.ddd.server.bundlesecurity.BundleSecurity;
import com.ddd.utils.AckRecordUtils;
import com.ddd.utils.BundleUtils;
import com.ddd.utils.Constants;

public class BundleTransmission {

  private static final String BUNDLE_RECEIVED_LOCATION =
      "C:\\Masters\\CS 297-298\\CS 298\\Implementation\\AppStorage\\Server\\Shared\\received-bundles";

  /* Bundle generation directory */
  private static final String BUNDLE_GENERATION_DIRECTORY =
      "C:\\Masters\\CS 297-298\\CS 298\\Implementation\\AppStorage\\Server\\BundleTransmission\\bundle-generation";

  private static final String RETRANSMISSION_BUNDLE_DIRECTORY = "retransmission-bundle";

  private static final String TO_BE_BUNDLED_DIRECTORY = "to-be-bundled";

  private static final String TO_SEND_DIRECTORY = "to-send";

  private long BUNDLE_SIZE_LIMIT = 10000;

  private BundleSecurity bundleSecurity;

  private ApplicationDataManager applicationDataManager;

  private BundleRouting bundleRouting;

  public BundleTransmission() {
    this.bundleSecurity = new BundleSecurity();
    this.applicationDataManager = new ApplicationDataManager();
    this.bundleRouting = new BundleRouting();
  }

  private void processReceivedBundle(Bundle bundle) {
    this.bundleSecurity.decryptBundleContents(bundle);
    String clientId = this.bundleSecurity.getClientIdFromRecvdBundleId(bundle.getBundleId());
    if (!this.bundleSecurity.isLatestReceivedBundleId(clientId, bundle.getBundleId())) {
      return;
    }
    this.bundleSecurity.registerRecvdBundleId(bundle.getBundleId());

    File clientAckSubDirectory =
        new File(
            BUNDLE_GENERATION_DIRECTORY
                + FileSystems.getDefault().getSeparator()
                + TO_BE_BUNDLED_DIRECTORY
                + FileSystems.getDefault().getSeparator()
                + clientId);

    if (!clientAckSubDirectory.exists()) {
      clientAckSubDirectory.mkdirs();
    }
    File ackFile =
        new File(
            clientAckSubDirectory.getAbsolutePath()
                + FileSystems.getDefault().getSeparator()
                + Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME);
    try {
      if (!ackFile.exists()) {
        ackFile.createNewFile();
      }
      AckRecordUtils.writeAckRecordToFile(new Acknowledgement(bundle.getBundleId()), ackFile);
    } catch (IOException e) {
      e.printStackTrace();
    }

    this.bundleSecurity.registerAck(bundle.getAckRecord().getBundleId());
    this.applicationDataManager.processAck(clientId, bundle.getAckRecord().getBundleId());
    if (!bundle.getADUs().isEmpty()) {
      this.applicationDataManager.storeADUs(clientId, bundle.getADUs());
    }
  }

  public void processReceivedBundles() {
    File receivedBundlesDirectory = new File(BUNDLE_RECEIVED_LOCATION);

    for (final File transportDir : receivedBundlesDirectory.listFiles()) {
      String transportId = transportDir.getName();
      this.bundleRouting.registerReceiptFromTransport(transportId);
      for (final File bundleFile : transportDir.listFiles()) {
        Bundle bundle = BundleUtils.readBundleFromFile(bundleFile).build();
        this.processReceivedBundle(bundle);
      }
    }
  }

  private String getAckRecordLocation(String clientId) {
    return BUNDLE_GENERATION_DIRECTORY
        + FileSystems.getDefault().getSeparator()
        + TO_BE_BUNDLED_DIRECTORY
        + FileSystems.getDefault().getSeparator()
        + clientId
        + FileSystems.getDefault().getSeparator()
        + Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME;
  }

  private Bundle.Builder generateBundleBuilder(String clientId) {
    List<ADU> ADUs = this.applicationDataManager.fetchADUs(clientId);

    Bundle.Builder builder = new Bundle.Builder();

    File ackFile = new File(this.getAckRecordLocation(clientId));

    Acknowledgement ackRecord = null;
    if (ackFile.exists()) {
      ackRecord = AckRecordUtils.readAckRecordFromFile(ackFile);
    } else {
      ackRecord = new Acknowledgement("HB");
      AckRecordUtils.writeAckRecordToFile(ackRecord, ackFile);
    }

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

  private BundleTransferDTO generateBundleForTransmission(
      String clientId, Set<String> bundleIdsPresent) {
    Set<String> deletionSet = new HashSet<>();
    List<Bundle> bundlesToSend = new ArrayList<>();
    File retransmissionBundleDirectory =
        new File(
            BUNDLE_GENERATION_DIRECTORY
                + FileSystems.getDefault().getSeparator()
                + RETRANSMISSION_BUNDLE_DIRECTORY);
    File clientRetransmissionSubDirectory =
        new File(
            retransmissionBundleDirectory + FileSystems.getDefault().getSeparator() + clientId);
    if (!retransmissionBundleDirectory.exists()) {
      retransmissionBundleDirectory.mkdirs();
    }
    if (!clientRetransmissionSubDirectory.exists()) {
      clientRetransmissionSubDirectory.mkdirs();
    }
    File bundleGenerationDirectory =
        new File(
            BUNDLE_GENERATION_DIRECTORY
                + FileSystems.getDefault().getSeparator()
                + TO_SEND_DIRECTORY
                + FileSystems.getDefault().getSeparator()
                + clientId);
    if (!bundleGenerationDirectory.exists()) {
      bundleGenerationDirectory.mkdirs();
    }
    File[] retransmissionBundleDirectoryContents = clientRetransmissionSubDirectory.listFiles();

    if (!this.bundleSecurity.isSenderWindowFull(clientId)) {
      Bundle.Builder generatedBundleBuilder = this.generateBundleBuilder(clientId);
      if (retransmissionBundleDirectoryContents.length > 0) {
        Bundle.Builder retransmissionBundleBuilder =
            BundleUtils.readBundleFromFile(retransmissionBundleDirectoryContents[0]);
        if (BundleUtils.doContentsMatch(generatedBundleBuilder, retransmissionBundleBuilder)) {
          if (bundleIdsPresent.contains(retransmissionBundleBuilder.getBundleId())) {
            deletionSet.addAll(bundleIdsPresent);
            deletionSet.remove(retransmissionBundleBuilder.getBundleId());
          } else {
            Bundle.Builder builder = new Bundle.Builder();
            builder.setAckRecord(retransmissionBundleBuilder.getAckRecord());
            builder.setBundleId(retransmissionBundleBuilder.getBundleId());
            builder.setADUs(retransmissionBundleBuilder.getADUs());
            builder.setSource(
                new File(
                    bundleGenerationDirectory
                        + FileSystems.getDefault().getSeparator()
                        + retransmissionBundleBuilder.getBundleId()));
            Bundle bundle = builder.build();
            BundleUtils.writeBundleToFile(bundle, bundleGenerationDirectory, bundle.getBundleId());
            bundlesToSend.add(bundle);
          }
        } else {
          deletionSet.addAll(bundleIdsPresent);
          String bundleId = this.bundleSecurity.generateBundleId(clientId);
          generatedBundleBuilder.setBundleId(bundleId);
          generatedBundleBuilder.setSource(
              new File(
                  bundleGenerationDirectory + FileSystems.getDefault().getSeparator() + bundleId));
          Bundle bundle = generatedBundleBuilder.build();
          this.bundleSecurity.encryptBundleContents(bundle);
          BundleUtils.writeBundleToFile(bundle, bundleGenerationDirectory, bundle.getBundleId());
          this.applicationDataManager.notifyBundleGenerated(bundle);
          bundlesToSend.add(bundle);
          this.storeForRetransmission(bundle, clientRetransmissionSubDirectory);
        }
      } else {
        deletionSet.addAll(bundleIdsPresent);
        String bundleId = this.bundleSecurity.generateBundleId(clientId);
        generatedBundleBuilder.setBundleId(bundleId);
        generatedBundleBuilder.setSource(
            new File(
                bundleGenerationDirectory.getAbsolutePath()
                    + FileSystems.getDefault().getSeparator()
                    + bundleId));
        Bundle bundle = generatedBundleBuilder.build();
        this.bundleSecurity.encryptBundleContents(bundle);
        BundleUtils.writeBundleToFile(bundle, bundleGenerationDirectory, bundle.getBundleId());
        this.applicationDataManager.notifyBundleGenerated(bundle);
        bundlesToSend.add(bundle);
        this.storeForRetransmission(bundle, clientRetransmissionSubDirectory);
      }
    } else {
      Bundle.Builder retransmissionBundleBuilder =
          BundleUtils.readBundleFromFile(
              retransmissionBundleDirectoryContents[
                  0]); // there is guaranteed to be a retransmission bundle since the sender window
      // is full
      if (bundleIdsPresent.contains(retransmissionBundleBuilder.getBundleId())) {
        deletionSet.addAll(bundleIdsPresent);
        deletionSet.remove(retransmissionBundleBuilder.getBundleId());
      } else {
        Bundle.Builder builder = new Bundle.Builder();
        builder.setAckRecord(retransmissionBundleBuilder.getAckRecord());
        builder.setBundleId(retransmissionBundleBuilder.getBundleId());
        builder.setADUs(retransmissionBundleBuilder.getADUs());
        builder.setSource(
            new File(
                bundleGenerationDirectory
                    + FileSystems.getDefault().getSeparator()
                    + retransmissionBundleBuilder.getBundleId()));
        Bundle bundle = builder.build();
        BundleUtils.writeBundleToFile(bundle, bundleGenerationDirectory, bundle.getBundleId());
        bundlesToSend.add(bundle);
      }
    }

    // TODO commit bundle id - in the exception handling phase.
    return new BundleTransferDTO(deletionSet, bundlesToSend);
  }

  public BundleTransferDTO generateBundlesForTransmission(
      String transportId, Set<String> bundleIdsPresent) {
    List<String> clientIds = this.bundleRouting.getClientIdsReachableFromTransport(transportId);
    Set<String> deletionSet = new HashSet<>();
    List<Bundle> bundlesToSend = new ArrayList<>();
    for (String clientId : clientIds) {
      BundleTransferDTO dtoForClient =
          this.generateBundleForTransmission(clientId, bundleIdsPresent);
      deletionSet.addAll(dtoForClient.getDeletionSet());
      bundlesToSend.addAll(dtoForClient.getBundles());
    }
    return new BundleTransferDTO(deletionSet, bundlesToSend);
  }

  public void deleteSentBundles(List<Bundle> bundles) {
    for (Bundle bundle : bundles) {
      try {
        FileUtils.deleteDirectory(bundle.getSource());
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }
}
