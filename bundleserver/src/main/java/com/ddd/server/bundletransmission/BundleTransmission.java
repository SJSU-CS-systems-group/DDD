package com.ddd.server.bundletransmission;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.Arrays;
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
      "/Users/adityasinghania/Downloads/Data/Shared/receive";

  /* Bundle generation directory */
  private static final String BUNDLE_GENERATION_DIRECTORY =
      "/Users/adityasinghania/Downloads/Data/BundleTransmission/bundle-generation";

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
    try {
      File bundleGenerationDir = new File(BUNDLE_GENERATION_DIRECTORY);
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
    try {
      FileUtils.deleteDirectory(bundle.getSource());
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public void processReceivedBundles(String transportId) {
    File receivedBundlesDirectory = new File(BUNDLE_RECEIVED_LOCATION);
    File transportDir = new File(receivedBundlesDirectory, transportId);
    // for (final File transportDir : receivedBundlesDirectory.listFiles()) {
      // String transportId = transportDir.getName();
      this.bundleRouting.registerReceiptFromTransport(transportId);
      for (final File bundleFile : transportDir.listFiles(new FileFilter() {
        @Override
        public boolean accept(File file) {
            return !file.isHidden();
        }
    })) {
        System.out.println(bundleFile.getAbsolutePath());
        Bundle bundle = BundleUtils.readBundleFromFile(bundleFile).build();
        this.processReceivedBundle(bundle);
      }
    // }
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
        try {
          FileUtils.deleteDirectory(retransmissionBundleBuilder.getSource());
        } catch (IOException e) {
          e.printStackTrace();
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
      try {
        FileUtils.deleteDirectory(retransmissionBundleBuilder.getSource());
      } catch (IOException e) {
        e.printStackTrace();
      }
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
      try {
        FileUtils.deleteDirectory(retransmissionBundleBuilder.getSource());
      } catch (IOException e) {
        e.printStackTrace();
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

  public List<File> getBundlesForTransmission(String transportId) {
    List<File> bundles = new ArrayList<>();
    File recvTransportSubDir = new File(BUNDLE_GENERATION_DIRECTORY+File.separator+TO_SEND_DIRECTORY+File.separator+transportId);
    System.out.println(BUNDLE_GENERATION_DIRECTORY+File.separator+TO_SEND_DIRECTORY+File.separator+transportId);
    File[] recvTransport =  recvTransportSubDir.listFiles(new FileFilter() {
      @Override
      public boolean accept(File file) {
          return !file.isHidden();
      }
  });
    if (recvTransport == null){
      return bundles;
    }
    for (File bundleFile : recvTransport) {
      bundles.add(bundleFile);
    }
    return bundles;
  }

  public void deleteSentBundles(List<Bundle> bundles) {
    // TODO
    //    for (Bundle bundle : bundles) {
    //      try {
    //        FileUtils.delete(bundle.getSource());
    //      } catch (IOException e) {
    //        e.printStackTrace();
    //      }
    //    }
    // TODO: Commented out for the moment.
  }
}
