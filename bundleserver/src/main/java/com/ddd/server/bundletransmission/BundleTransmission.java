package com.ddd.server.bundletransmission;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.sql.SQLException;
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
import com.ddd.server.bundlerouting.ServerWindow;
import com.ddd.server.bundlerouting.WindowUtils.WindowExceptions.ClientWindowNotFound;
import com.ddd.server.bundlerouting.WindowUtils.WindowExceptions.InvalidLength;
import com.ddd.server.bundlesecurity.BundleIDGenerator;
import com.ddd.server.bundlesecurity.BundleSecurity;
import com.ddd.server.bundlesecurity.SecurityExceptions.BundleIDCryptographyException;
import com.ddd.server.bundlesecurity.SecurityExceptions.IDGenerationException;
import com.ddd.server.bundlesecurity.SecurityExceptions.InvalidClientIDException;
import com.ddd.server.bundlesecurity.SecurityUtils;
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

  private ServerWindow serverWindow;

  private int WINDOW_LENGTH = 3;

  public BundleTransmission(
      BundleSecurity bundleSecurity,
      ApplicationDataManager applicationDataManager,
      BundleRouting bundleRouting,
      LargestBundleIdReceivedRepository largestBundleIdReceivedRepository,
      BundleServerConfig config,
      BundleGeneratorService bundleGenServ,
      ServerWindow serverWindow) {
    this.bundleSecurity = bundleSecurity;
    this.applicationDataManager = applicationDataManager;
    this.config = config;
    this.bundleRouting = bundleRouting;
    this.bundleGenServ = bundleGenServ;
    this.serverWindow = serverWindow;
  }

  @Transactional(rollbackFor = Exception.class)
  public void processReceivedBundle(String transportId, Bundle bundle) {
    File bundleRecvProcDir =
        new File(
            this.config.getBundleTransmission().getReceivedProcessingDirectory()
                + File.separator
                + transportId);
    bundleRecvProcDir.mkdirs();
    
    UncompressedBundle uncompressedBundle =
        this.bundleGenServ.extractBundle(bundle, bundleRecvProcDir.getAbsolutePath());
    Payload payload = this.bundleSecurity.decryptPayload(uncompressedBundle);
    String clientId = "";
    try {
      clientId = SecurityUtils.generateID(uncompressedBundle.getSource() + File.separator + "clientIdentity.pub");
    } catch (IDGenerationException e2) {
      // TODO Auto-generated catch block
      e2.printStackTrace();
    }
    
    Optional<String> opt = this.applicationDataManager.getLargestRecvdBundleId(clientId);

    try {
      if (!opt.isEmpty()
          && (this.serverWindow.compareBundleIDs(
                  opt.get(), payload.getBundleId(), clientId, BundleIDGenerator.UPSTREAM)
              == 1)) {
        return;
      }
    } catch (BundleIDCryptographyException e1) {
      // TODO Auto-generated catch block
      e1.printStackTrace();
    }
    
    UncompressedPayload uncompressedPayload =
        this.bundleGenServ.extractPayload(
            payload, uncompressedBundle.getSource().getAbsolutePath());
    
    File ackRecordFile = new File(this.getAckRecordLocation(clientId));
    ackRecordFile.getParentFile().mkdirs();
    try {
      ackRecordFile.createNewFile();
    } catch (IOException e1) {
      // TODO Auto-generated catch block
      e1.printStackTrace();
    }

    AckRecordUtils.writeAckRecordToFile(
        new Acknowledgement(uncompressedPayload.getBundleId()), ackRecordFile);

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
      AckRecordUtils.writeAckRecordToFile(new Acknowledgement(uncompressedPayload.getBundleId()), ackFile);
    } catch (IOException e) {
      e.printStackTrace();
    }

    if (!"HB".equals(uncompressedPayload.getAckRecord().getBundleId())) {

      try {
        this.serverWindow.processACK(clientId, uncompressedPayload.getAckRecord().getBundleId());
      } catch (ClientWindowNotFound | InvalidLength | BundleIDCryptographyException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
    }
    
    this.bundleRouting.processClientMetadata(uncompressedPayload.getSource().getAbsolutePath(), clientId);

    this.applicationDataManager.processAcknowledgement(
        clientId, uncompressedPayload.getAckRecord().getBundleId());
    if (!uncompressedPayload.getADUs().isEmpty()) {      
      this.applicationDataManager.storeADUs(clientId, uncompressedPayload.getBundleId(), uncompressedPayload.getADUs());
    }
  }

  public void processReceivedBundles(String transportId) {
    File receivedBundlesDirectory =
        new File(this.config.getBundleTransmission().getBundleReceivedLocation());
    for (final File transportDir : receivedBundlesDirectory.listFiles()) {
      if (!transportId.equals(transportDir.getName())) {
        continue;
      }


      for (final File bundleFile : transportDir.listFiles()) {
        try {
          Bundle bundle = new Bundle(bundleFile);
          this.processReceivedBundle(transportId, bundle);
        } catch (Exception e) {
          e.printStackTrace();
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

  private String generateBundleId(String clientId)
      throws ClientWindowNotFound, BundleIDCryptographyException, InvalidClientIDException, SQLException {
    return this.serverWindow.getCurrentbundleID(clientId);
  }

  private BundleTransferDTO generateBundleForTransmission(
      String transportId, String clientId, Set<String> bundleIdsPresent)
      throws ClientWindowNotFound {
    Set<String> deletionSet = new HashSet<>();
    List<BundleDTO> bundlesToSend = new ArrayList<>();

    Optional<UncompressedPayload.Builder> optional =
        this.applicationDataManager.getLastSentBundlePayloadBuilder(clientId);
    UncompressedPayload.Builder generatedPayloadBuilder = this.generatePayloadBuilder(clientId);

    Optional<UncompressedPayload> toSendOpt = Optional.empty();
    String bundleId = "";
    boolean isRetransmission = false;

    try {
      this.serverWindow.addClient(clientId, this.WINDOW_LENGTH);
    } catch (Exception e) {
      e.printStackTrace();
    }
    boolean isSenderWindowFull = this.serverWindow.isClientWindowFull(clientId);

    if (isSenderWindowFull) {
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
        try {
          bundleId = this.generateBundleId(clientId);
        } catch (ClientWindowNotFound
            | BundleIDCryptographyException
            | InvalidClientIDException | SQLException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }

      } else {
        UncompressedPayload.Builder retxmnBundlePayloadBuilder = optional.get();
        if (optional.isPresent()
            && BundleGeneratorService.doContentsMatch(generatedPayloadBuilder, optional.get())) {
          bundleId = retxmnBundlePayloadBuilder.getBundleId();
          isRetransmission = true;
        } else { // new data to send
          try {
            bundleId = this.generateBundleId(clientId);
          } catch (ClientWindowNotFound
              | BundleIDCryptographyException
              | InvalidClientIDException | SQLException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
          }
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
            this.bundleSecurity.encryptPayload(clientId,
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
      String clientId = this.applicationDataManager.getClientIdFromSentBundleId(bundleId);
      Set<String> bundleIds = clientIdToBundleIds.getOrDefault(clientId, new HashSet<>());
      bundleIds.add(bundleId);
      clientIdToBundleIds.put(clientId, bundleIds);
    }
    for (String clientId : clientIds) {
      BundleTransferDTO dtoForClient;
      try {
        dtoForClient =
            this.generateBundleForTransmission(
                transportId, clientId, clientIdToBundleIds.get(clientId));
        deletionSet.addAll(dtoForClient.getDeletionSet());
        bundlesToSend.addAll(dtoForClient.getBundles());
      } catch (ClientWindowNotFound e) {
        e.printStackTrace();
      }
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

  public void notifyBundleSent(BundleDTO bundleDTO) {
    //      try {
    //        FileUtils.delete(bundle.getSource());
    //      } catch (IOException e) {
    //        e.printStackTrace();
    //      }
    // TODO: Commented out for the moment.

    try {
      String bundleId = bundleDTO.getBundleId();
      String clientId =
          BundleIDGenerator.getClientIDFromBundleID(bundleId, BundleIDGenerator.DOWNSTREAM);
      this.serverWindow.updateClientWindow(clientId, bundleId);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
