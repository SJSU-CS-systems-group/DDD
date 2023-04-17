package com.ddd.server.bundletransmission;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ddd.model.ADU;
import com.ddd.model.Acknowledgement;
import com.ddd.model.Bundle;
import com.ddd.model.BundleDTO;
import com.ddd.model.BundleTransferDTO;
import com.ddd.model.Payload;
import com.ddd.model.UncompressedBundle;
import com.ddd.model.UncompressedPayload;
import com.ddd.server.applicationdatamanager.ApplicationDataManager;
import com.ddd.server.bundlerouting.BundleRouting;
import com.ddd.server.bundlesecurity.BundleID;
import com.ddd.server.bundlesecurity.BundleSecurity;
import com.ddd.server.config.BundleServerConfig;
import com.ddd.server.repository.LargestBundleIdReceivedRepository;
import com.ddd.utils.AckRecordUtils;
import com.ddd.utils.Constants;

@Service
public class BundleTransmission {

  private BundleServerConfig config;

  private BundleSecurity bundleSecurity;

  private ApplicationDataManager applicationDataManager;

  private BundleRouting bundleRouting;

  private BundleGeneratorService bundleGenServ;

  public BundleTransmission(
      BundleSecurity bundleSecurity,
      ApplicationDataManager applicationDataManager,
      BundleRouting bundleRouting,
      LargestBundleIdReceivedRepository largestBundleIdReceivedRepository,
      BundleServerConfig config,
      BundleGeneratorService bundleGenServ) {
    this.bundleSecurity = bundleSecurity;
    this.applicationDataManager = applicationDataManager;
    this.config = config;
    this.bundleRouting = bundleRouting;
    this.bundleGenServ = bundleGenServ;
  }

  @Transactional(rollbackFor = Exception.class)
  public void processReceivedBundle(UncompressedPayload bundle) {
    String clientId = BundleID.getClientIDFromBundleID(bundle.getBundleId(), BundleID.UPSTREAM);
    Optional<String> opt = this.applicationDataManager.getLargestRecvdBundleId(clientId);

    if (!opt.isEmpty()
        && (BundleID.compareBundleIDs(opt.get(), bundle.getBundleId(), BundleID.UPSTREAM) < 1)) {
      return;
    }

    AckRecordUtils.writeAckRecordToFile(
        new Acknowledgement(bundle.getBundleId()), new File(this.getAckRecordLocation(clientId)));

    this.bundleSecurity.decryptBundleContents(bundle);

    File clientAckSubDirectory =
        new File(
            this.config.getBundleTransmission().getToBeBundledDirectory()
                + File.separator
                + clientId);

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

    //     this.bundleSecurity.processACK(clientId, bundle.getAckRecord().getBundleId());
    //    this.bundleRouting.addClient(clientId, 0);
    //    this.bundleRouting.processClientMetadata(transportId);
    // if ack not a HB:
    this.applicationDataManager.processAcknowledgement(
        clientId, bundle.getAckRecord().getBundleId());
    if (!bundle.getADUs().isEmpty()) {
      this.applicationDataManager.storeADUs(clientId, bundle.getBundleId(), bundle.getADUs());
    }
  }

  public void processReceivedBundles(String transportId) {
    File receivedBundlesDirectory =
        new File(this.config.getBundleTransmission().getBundleReceivedLocation());
    for (final File transportDir : receivedBundlesDirectory.listFiles()) {
      if (!transportId.equals(transportDir.getName())) {
        continue;
      }
      File bundleRecvProcDir =
          new File(
              this.config.getBundleTransmission().getReceivedProcessingDirectory()
                  + File.separator
                  + transportId);
      bundleRecvProcDir.mkdirs();

      for (final File bundleFile : transportDir.listFiles()) {
        try {
          Bundle bundle = new Bundle(bundleFile);
          UncompressedBundle uncompressedBundle =
              this.bundleGenServ.extractBundle(bundle, bundleRecvProcDir.getAbsolutePath());
          Payload payload = this.bundleSecurity.decryptPayload(uncompressedBundle);
          UncompressedPayload uncompressedPayload =
              this.bundleGenServ.extractPayload(
                  payload, uncompressedBundle.getSource().getAbsolutePath());
          this.processReceivedBundle(uncompressedPayload);
        } catch (Exception e) {
          System.out.println(e);
        } finally {
          try {
            FileUtils.delete(bundleFile);
          } catch (IOException e) {
            System.out.println(e);
          }
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

  private UncompressedPayload.Builder generatePayloadBuilder(String clientId) {
    List<ADU> ADUs = this.applicationDataManager.fetchADUs(clientId);

    UncompressedPayload.Builder builder = new UncompressedPayload.Builder();

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
    List<BundleDTO> bundlesToSend = new ArrayList<>();

    Optional<UncompressedPayload.Builder> optional =
        this.applicationDataManager.getLastSentBundlePayloadBuilder(clientId);
    UncompressedPayload.Builder generatedPayloadBuilder = this.generatePayloadBuilder(clientId);

    Optional<UncompressedPayload> toSendOpt = Optional.empty();
    String bundleId = "";
    boolean isRetransmission = false;

    if (this.bundleSecurity.isSenderWindowFull(clientId)) {
      UncompressedPayload.Builder retxmnBundlePayloadBuilder =
          optional.get(); // there was definitely a bundle sent previously if sender window is full

      bundleId = retxmnBundlePayloadBuilder.getBundleId();
      retxmnBundlePayloadBuilder.setSource(
          new File(
              this.config.getBundleTransmission().getUncompressedPayloadDirectory()
                  + File.separator
                  + bundleId));
      UncompressedPayload toSend = retxmnBundlePayloadBuilder.build();
      toSendOpt = Optional.of(toSend);
      isRetransmission = true;

    } else if (!generatedPayloadBuilder
        .getADUs()
        .isEmpty()) { // to ensure we never send a pure ack bundle i.e. a bundle with no ADUs
      if (optional.isEmpty()) { // no bundle ever sent
        bundleId = this.bundleSecurity.generateBundleId(clientId);
      } else {
        UncompressedPayload.Builder retxmnBundlePayloadBuilder = optional.get();
        if (optional.isPresent()
            && BundleGeneratorService.doContentsMatch(generatedPayloadBuilder, optional.get())) {
          bundleId = retxmnBundlePayloadBuilder.getBundleId();
          isRetransmission = true;
        } else { // new data to send
          bundleId = this.bundleSecurity.generateBundleId(clientId);
        }
      }

      generatedPayloadBuilder.setBundleId(bundleId);
      generatedPayloadBuilder.setSource(
          new File(
              this.config.getBundleTransmission().getUncompressedPayloadDirectory()
                  + File.separator
                  + bundleId));
      UncompressedPayload toSend = generatedPayloadBuilder.build();
      toSendOpt = Optional.of(toSend);
    }

    if (toSendOpt.isPresent()) {
      UncompressedPayload toSendBundlePayload = toSendOpt.get();
      if (bundleIdsPresent.contains(toSendBundlePayload.getBundleId())) {
        deletionSet.addAll(bundleIdsPresent);
        deletionSet.remove(toSendBundlePayload.getBundleId());
        // We dont add toSend to bundlesToSend because the bundle is already on the transport.
      } else {
        if (!isRetransmission) {
          this.applicationDataManager.notifyBundleGenerated(clientId, toSendBundlePayload);
          this.bundleRouting.updateClientWindow(clientId, bundleId);
        }

        this.bundleGenServ.writeUncompressedPayload(
            toSendBundlePayload,
            new File(this.config.getBundleTransmission().getUncompressedPayloadDirectory()),
            toSendBundlePayload.getBundleId());

        Payload payload =
            this.bundleGenServ.compressPayload(
                toSendBundlePayload,
                this.config.getBundleTransmission().getCompressedPayloadDirectory());
        UncompressedBundle uncompressedBundle =
            this.bundleSecurity.encryptPayload(
                payload, this.config.getBundleTransmission().getEncryptedPayloadDirectory());

        File toSendTxpDir =
            new File(
                this.config.getBundleTransmission().getToSendDirectory()
                    + File.separator
                    + transportId);
        toSendTxpDir.mkdirs();

        Bundle toSend =
            this.bundleGenServ.compressBundle(uncompressedBundle, toSendTxpDir.getAbsolutePath());
        bundlesToSend.add(new BundleDTO(bundleId, toSend));
        deletionSet.addAll(bundleIdsPresent);
      }
    }

    return new BundleTransferDTO(deletionSet, bundlesToSend);
  }

  public BundleTransferDTO generateBundlesForTransmission(
      String transportId, Set<String> bundleIdsPresent) {
    List<String> clientIds = this.bundleRouting.getClients(transportId);
    Set<String> deletionSet = new HashSet<>();
    List<BundleDTO> bundlesToSend = new ArrayList<>();
    Map<String, Set<String>> clientIdToBundleIds = new HashMap<>();

    for (String clientId : clientIds) {
      clientIdToBundleIds.put(clientId, new HashSet<>());
    }

    for (String bundleId : bundleIdsPresent) {
      String clientId = this.bundleSecurity.getClientIdFromBundleId(bundleId);
      Set<String> bundleIds = clientIdToBundleIds.getOrDefault(clientId, new HashSet<>());
      bundleIds.add(bundleId);
      clientIdToBundleIds.put(clientId, bundleIds);
    }
    for (String clientId : clientIds) {
      BundleTransferDTO dtoForClient =
          this.generateBundleForTransmission(
              transportId, clientId, clientIdToBundleIds.get(clientId));
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

  public void notifyBundleSent(Bundle bundle) {
    //      try {
    //        FileUtils.delete(bundle.getSource());
    //      } catch (IOException e) {
    //        e.printStackTrace();
    //      }
    // TODO: Commented out for the moment.
  }
}
