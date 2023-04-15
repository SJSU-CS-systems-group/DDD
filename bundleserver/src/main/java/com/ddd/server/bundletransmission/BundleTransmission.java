package com.ddd.server.bundletransmission;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Service;
import com.ddd.model.ADU;
import com.ddd.model.Acknowledgement;
import com.ddd.model.Bundle;
import com.ddd.model.BundleTransferDTO;
import com.ddd.server.applicationdatamanager.ApplicationDataManager;
import com.ddd.server.bundlerouting.BundleRouting;
import com.ddd.server.bundlesecurity.BundleSecurity;
import com.ddd.server.config.BundleServerConfig;
import com.ddd.utils.AckRecordUtils;
import com.ddd.utils.BundleUtils;
import com.ddd.utils.Constants;

@Service
public class BundleTransmission {

  private BundleServerConfig config;

  private BundleSecurity bundleSecurity;

  private ApplicationDataManager applicationDataManager;

  private BundleRouting bundleRouting;

  public BundleTransmission(
      BundleSecurity bundleSecurity,
      ApplicationDataManager applicationDataManager,
      BundleRouting bundleRouting,
      BundleServerConfig config) {
    this.bundleSecurity = bundleSecurity;
    this.applicationDataManager = applicationDataManager;
    this.config = config;
    this.bundleRouting = bundleRouting;
  }

  private void processReceivedBundle(Bundle bundle) {
    this.bundleSecurity.decryptBundleContents(bundle);
    String clientId = this.bundleSecurity.getClientIdFromRecvdBundleId(bundle.getBundleId());
    if (!this.bundleSecurity.isLatestReceivedBundleId(clientId, bundle.getBundleId())) {
      return;
    }
    this.bundleSecurity.registerRecvdBundleId(bundle.getBundleId());

    File clientAckSubDirectory =
        new File(this.config.getBundleTransmission().getToBeBundledDirectory() + clientId);

    if (!clientAckSubDirectory.exists()) {
      clientAckSubDirectory.mkdirs();
    }
    File ackFile =
        new File(
            clientAckSubDirectory.getAbsolutePath()
                + File.separator
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
    this.applicationDataManager.processAcknowledgement(
        clientId, bundle.getAckRecord().getBundleId());
    if (!bundle.getADUs().isEmpty()) {
      this.applicationDataManager.storeADUs(clientId, bundle.getADUs());
    }
  }

  public void processReceivedBundles(String transportId) {
    File receivedBundlesDirectory =
        new File(this.config.getBundleTransmission().getBundleReceivedLocation());

    for (final File transportDir : receivedBundlesDirectory.listFiles()) {
      if (!transportId.equals(transportDir.getName())) {
        continue;
      }
      this.bundleRouting.registerReceiptFromTransport(transportId);
      for (final File bundleFile : transportDir.listFiles()) {
        Bundle bundle = BundleUtils.readBundleFromFile(bundleFile).build();
        this.processReceivedBundle(bundle);
        try {
          FileUtils.deleteDirectory(bundle.getSource());
          FileUtils.delete(bundleFile);
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
  }

  private String getAckRecordLocation(String clientId) {
    return this.config.getBundleTransmission().getToBeBundledDirectory()
        + File.separator
        + clientId
        + File.separator
        + Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME;
  }

  private Bundle.Builder generateBundleBuilder(String clientId) {
    List<ADU> ADUs = this.applicationDataManager.fetchADUs(clientId);

    Bundle.Builder builder = new Bundle.Builder();

    File ackFile = new File(this.getAckRecordLocation(clientId));
    new File(ackFile.getParent()).mkdirs();

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
      if (adu.getSize() + totalSize > this.config.getBundleTransmission().getBundleSizeLimit()) {
        break;
      }
      totalSize += adu.getSize();
      adusToPack.add(adu);
    }
    builder.setADUs(adusToPack);

    return builder;
  }

  private BundleTransferDTO generateBundleForTransmission(
      String transportId, String clientId, Set<String> bundleIdsPresent) {
    Set<String> deletionSet = new HashSet<>();
    List<Bundle> bundlesToSend = new ArrayList<>();

    File bundleGenerationDirectory =
        new File(
            this.config.getBundleTransmission().getToSendDirectory()
                + File.separator
                + transportId);
    if (!bundleGenerationDirectory.exists()) {
      bundleGenerationDirectory.mkdirs();
    }

    Optional<Bundle.Builder> optional =
        this.applicationDataManager.getLastSentBundleBuilder(clientId);
    Bundle.Builder generatedBundleBuilder = this.generateBundleBuilder(clientId);
    if (!this.bundleSecurity.isSenderWindowFull(clientId)) {
      if (optional.isPresent()) {
        Bundle.Builder retransmissionBundleBuilder = optional.get();
        if (BundleUtils.doContentsMatch(generatedBundleBuilder, retransmissionBundleBuilder)) {
          if (bundleIdsPresent.contains(retransmissionBundleBuilder.getBundleId())) {
            deletionSet.addAll(bundleIdsPresent);
            deletionSet.remove(retransmissionBundleBuilder.getBundleId());
          } else {
            String bundleId = retransmissionBundleBuilder.getBundleId();
            Bundle.Builder builder = new Bundle.Builder();
            builder.setAckRecord(generatedBundleBuilder.getAckRecord());
            builder.setBundleId(bundleId);
            builder.setADUs(generatedBundleBuilder.getADUs());
            builder.setSource(
                new File(bundleGenerationDirectory + File.separator + bundleId + ".jar"));
            Bundle bundle = builder.build();
            this.bundleSecurity.encryptBundleContents(bundle);
            BundleUtils.writeBundleToFile(bundle, bundleGenerationDirectory, bundle.getBundleId());
            this.applicationDataManager.notifyBundleGenerated(clientId, bundle);
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
          this.applicationDataManager.notifyBundleGenerated(clientId, bundle);
          bundlesToSend.add(bundle);
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
        this.applicationDataManager.notifyBundleGenerated(clientId, bundle);
        bundlesToSend.add(bundle);
      }
    } else {
      Bundle.Builder retransmissionBundleBuilder =
          optional
              .get(); // there is guaranteed to be a retransmission bundle since the sender window
      // is full

      if (bundleIdsPresent.contains(retransmissionBundleBuilder.getBundleId())) {
        deletionSet.addAll(bundleIdsPresent);
        deletionSet.remove(retransmissionBundleBuilder.getBundleId());
      } else {
        String bundleId = retransmissionBundleBuilder.getBundleId();
        Bundle.Builder builder = new Bundle.Builder();
        builder.setAckRecord(generatedBundleBuilder.getAckRecord());
        builder.setBundleId(bundleId);
        builder.setADUs(generatedBundleBuilder.getADUs());
        builder.setSource(new File(bundleGenerationDirectory + File.separator + bundleId + ".jar"));
        Bundle bundle = builder.build();
        this.bundleSecurity.encryptBundleContents(bundle);
        BundleUtils.writeBundleToFile(bundle, bundleGenerationDirectory, bundle.getBundleId());
        this.applicationDataManager.notifyBundleGenerated(clientId, bundle);
        bundlesToSend.add(bundle);
      }
    }

    return new BundleTransferDTO(deletionSet, bundlesToSend);
  }

  public BundleTransferDTO generateBundlesForTransmission(
      String transportId, Set<String> bundleIdsPresent) {
    List<String> clientIds = this.bundleRouting.getClientIdsReachableFromTransport(transportId);
    Set<String> deletionSet = new HashSet<>();
    List<Bundle> bundlesToSend = new ArrayList<>();
    for (String clientId : clientIds) {
      BundleTransferDTO dtoForClient =
          this.generateBundleForTransmission(transportId, clientId, bundleIdsPresent);
      deletionSet.addAll(dtoForClient.getDeletionSet());
      bundlesToSend.addAll(dtoForClient.getBundles());
    }
    return new BundleTransferDTO(deletionSet, bundlesToSend);
  }

  public List<File> getBundlesForTransmission(String transportId) {
    List<File> bundles = new ArrayList<>();
    File recvTransportSubDir =
        new File(
            this.config.getBundleTransmission().getToSendDirectory()
                + File.separator
                + transportId);

    File[] recvTransport =
        recvTransportSubDir.listFiles(
            new FileFilter() {
              @Override
              public boolean accept(File file) {
                return !file.isHidden();
              }
            });
    if (recvTransport == null) {
      return bundles;
    }
    for (File bundleFile : recvTransport) {
      bundles.add(bundleFile);
    }
    return bundles;
  }

  public void notifyBundleSent(List<Bundle> bundles) {
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
