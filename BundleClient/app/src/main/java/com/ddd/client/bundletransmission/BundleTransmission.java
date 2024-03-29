package com.ddd.client.bundletransmission;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import com.ddd.bundleclient.HelloworldActivity;
import com.ddd.client.applicationdatamanager.ApplicationDataManager;
import com.ddd.client.bundlerouting.ClientBundleGenerator;
import com.ddd.client.bundlerouting.ClientRouting;
import com.ddd.client.bundlerouting.ClientWindow;
import com.ddd.client.bundlerouting.RoutingExceptions;
import com.ddd.client.bundlesecurity.BundleIDGenerator;
import com.ddd.client.bundlesecurity.BundleSecurity;
import com.ddd.client.bundlesecurity.SecurityExceptions;
import com.ddd.model.ADU;
import com.ddd.model.Acknowledgement;
import com.ddd.model.Bundle;
import com.ddd.model.BundleDTO;
import com.ddd.model.Payload;
import com.ddd.model.UncompressedBundle;
import com.ddd.model.UncompressedPayload;
import com.ddd.utils.AckRecordUtils;
import com.ddd.utils.BundleUtils;
import com.ddd.utils.Constants;
import android.util.Log;

public class BundleTransmission {

  private BundleSecurity bundleSecurity;

  private ApplicationDataManager applicationDataManager;

  /* Bundle generation directory */
  private static final String BUNDLE_GENERATION_DIRECTORY = "/BundleTransmission/bundle-generation";

  private static final String TO_BE_BUNDLED_DIRECTORY = "to-be-bundled";

  private static final String TO_SEND_DIRECTORY = "to-send";
  
  private static final String UNCOMPRESSED_PAYLOAD = "uncompressed-payload";
  
  private static final String COMPRESSED_PAYLOAD = "compressed-payload";
  
  private static final String ENCRYPTED_PAYLOAD = "encrypted-payload";
  
  private static final String RECEIVED_PROCESSING = "received-processing";

  private static String LARGEST_BUNDLE_ID_RECEIVED =
      "/Shared/DB/LARGEST_BUNDLE_ID_RECEIVED.txt";

  private long BUNDLE_SIZE_LIMIT = 1000000000L;

  private String ROOT_DIR = "";

  private ClientRouting clientRouting = null;

  public BundleTransmission(String rootFolder) {
    this.ROOT_DIR = rootFolder;
    this.bundleSecurity = new BundleSecurity(this.ROOT_DIR);
    this.applicationDataManager = new ApplicationDataManager(this.ROOT_DIR);

    try {
      this.clientRouting = ClientRouting.initializeInstance(rootFolder);

      File bundleGenerationDir = new File(this.ROOT_DIR + BUNDLE_GENERATION_DIRECTORY);
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
      
      File uncompressedPayloadDir = new File(bundleGenerationDir + File.separator + UNCOMPRESSED_PAYLOAD);
      uncompressedPayloadDir.mkdirs();
      File compressedPayloadDir = new File(bundleGenerationDir + File.separator + COMPRESSED_PAYLOAD);
      compressedPayloadDir.mkdirs();
      File encryptedPayloadDir = new File(bundleGenerationDir + File.separator + ENCRYPTED_PAYLOAD);
      encryptedPayloadDir.mkdirs();
      File receivedProcDir = new File(bundleGenerationDir + File.separator + RECEIVED_PROCESSING);
      receivedProcDir.mkdirs();
    } catch (IOException | RoutingExceptions.ClientMetaDataFileException e) {
      e.printStackTrace();
    }
  }

  private String getAckRecordLocation() {
    return this.ROOT_DIR
        + BUNDLE_GENERATION_DIRECTORY
        + "/"
        + TO_BE_BUNDLED_DIRECTORY
        + "/"
        + Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME;
  }
  
  public void registerBundleId(String bundleId) {
    try (BufferedWriter bufferedWriter =
        new BufferedWriter(new FileWriter(new File(this.ROOT_DIR + LARGEST_BUNDLE_ID_RECEIVED)))) {
      bufferedWriter.write(bundleId);
    } catch (IOException e) {
      e.printStackTrace();
    }
    System.out.println("[BS] Registered bundle identifier: " + bundleId);
  }
  private String getLargestBundleIdReceived() {
    String bundleId = "";
    try (BufferedReader bufferedReader = new BufferedReader(new FileReader(new File(this.ROOT_DIR + LARGEST_BUNDLE_ID_RECEIVED)))){
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

  private void processReceivedBundle(String transportId, Bundle bundle) {
    String largestBundleIdReceived = this.getLargestBundleIdReceived();
    UncompressedBundle uncompressedBundle =
        BundleUtils.extractBundle(bundle, this.ROOT_DIR + BUNDLE_GENERATION_DIRECTORY + File.separator + RECEIVED_PROCESSING);
    Payload payload = this.bundleSecurity.decryptPayload(uncompressedBundle);
    try {
      Log.d(HelloworldActivity.TAG, "Updating client routing metadata for transport  " + transportId);
      clientRouting.updateMetaData(transportId);
    } catch (RoutingExceptions.ClientMetaDataFileException e) {
      e.printStackTrace();
    }
    String bundleId = payload.getBundleId();

    ClientBundleGenerator clientBundleGenerator = this.bundleSecurity.getClientBundleGenerator();
    boolean isLatestBundleId = false;
    try {
      isLatestBundleId = (!StringUtils.isBlank(largestBundleIdReceived) && clientBundleGenerator.compareBundleIDs(bundleId, largestBundleIdReceived, BundleIDGenerator.DOWNSTREAM) == 1);
    } catch (SecurityExceptions.BundleIDCryptographyException e) {
      e.printStackTrace();
    }
    if (isLatestBundleId) {
      return;
    }
    UncompressedPayload uncompressedPayload =
        BundleUtils.extractPayload(
            payload, uncompressedBundle.getSource().getAbsolutePath());
    
    AckRecordUtils.writeAckRecordToFile(
        new Acknowledgement(bundleId), new File(this.getAckRecordLocation()));
    this.registerBundleId(bundleId);
    
    String ackedBundleId = uncompressedPayload.getAckRecord().getBundleId();

    this.applicationDataManager.processAcknowledgement(ackedBundleId);
    this.applicationDataManager.storeADUs(uncompressedPayload.getADUs());

  }

  public void processReceivedBundles(String transportId, String bundlesLocation) {
    File bundleStorageDirectory = new File(bundlesLocation);
    Log.d(HelloworldActivity.TAG, "inside receives" + bundlesLocation);
    if (bundleStorageDirectory.listFiles() == null || bundleStorageDirectory.listFiles().length == 0){
      Log.d(HelloworldActivity.TAG,"No Bundle received");
      return;
    }
    for (final File bundleFile : bundleStorageDirectory.listFiles()) {
      Bundle bundle = new Bundle(bundleFile);
      Log.d(HelloworldActivity.TAG,"Processing: "+bundle.getSource().getName());
      this.processReceivedBundle(transportId, bundle);
      Log.d(HelloworldActivity.TAG, "Deleting Directory");
      FileUtils.deleteQuietly(bundle.getSource());
      Log.d(HelloworldActivity.TAG, "Deleted Directory");
    }
    String largestBundleId = getLargestBundleIdReceived();
    this.bundleSecurity.registerLargestBundleIdReceived(largestBundleId);
  }

  private UncompressedPayload.Builder generateBundleBuilder() {

    UncompressedPayload.Builder builder = new UncompressedPayload.Builder();
    Acknowledgement ackRecord =
        AckRecordUtils.readAckRecordFromFile(new File(this.getAckRecordLocation()));
    builder.setAckRecord(ackRecord);

    List<ADU> ADUs = this.applicationDataManager.fetchADUs(ackRecord.getSize());

    builder.setADUs(ADUs);

    return builder;
  }

  private BundleDTO generateNewBundle(File targetDir) {
    UncompressedPayload.Builder builder = this.generateBundleBuilder();
    String bundleId = null;
    try {
      bundleId = this.bundleSecurity.generateNewBundleId();
    } catch (SecurityExceptions.IDGenerationException |
             SecurityExceptions.BundleIDCryptographyException e) {
      e.printStackTrace();
    }

    return generateNewBundle(builder, targetDir, bundleId);
  }

  private BundleDTO generateNewBundle(
      UncompressedPayload.Builder builder, File targetDir, String bundleId) {
    builder.setBundleId(bundleId);
    builder.setSource(new File(this.ROOT_DIR + BUNDLE_GENERATION_DIRECTORY + File.separator + UNCOMPRESSED_PAYLOAD + File.separator + bundleId));
    UncompressedPayload toSendBundlePayload = builder.build();
    BundleUtils.writeUncompressedPayload(
        toSendBundlePayload,
        new File(this.ROOT_DIR + BUNDLE_GENERATION_DIRECTORY + File.separator + UNCOMPRESSED_PAYLOAD),
        bundleId);
    try {
      Log.d(HelloworldActivity.TAG, "Placing routing.metadata in " + toSendBundlePayload.getSource().getAbsolutePath());
      clientRouting.bundleMetaData(toSendBundlePayload.getSource().getAbsolutePath());
    } catch (RoutingExceptions.ClientMetaDataFileException e) {
      System.out.println("[BR]: Failed to add Routing metadata to bundle!");
      e.printStackTrace();
    }

    Payload payload =
        BundleUtils.compressPayload(
            toSendBundlePayload,
            this.ROOT_DIR + BUNDLE_GENERATION_DIRECTORY + File.separator + COMPRESSED_PAYLOAD);
    UncompressedBundle uncompressedBundle =
        this.bundleSecurity.encryptPayload(
            payload, this.ROOT_DIR + BUNDLE_GENERATION_DIRECTORY + File.separator + ENCRYPTED_PAYLOAD);

    Bundle toSend =
        BundleUtils.compressBundle(uncompressedBundle, targetDir.getAbsolutePath());
    this.applicationDataManager.notifyBundleSent(toSendBundlePayload);
    System.out.println("[BT] Generated new bundle for transmission with bundle id: " + bundleId);
    return new BundleDTO(bundleId, toSend);
  }

  public BundleDTO generateBundleForTransmission() {
    Log.d(HelloworldActivity.TAG, "Started process of generating bundle");
    File toSendDir =
        new File(this.ROOT_DIR + BUNDLE_GENERATION_DIRECTORY + File.separator + TO_SEND_DIRECTORY);

    BundleDTO toSend = null;
    Optional<UncompressedPayload.Builder> optional =
        this.applicationDataManager.getLastSentBundleBuilder();

    // check if it's first bundle generation
    if (!optional.isPresent()) {
      toSend = this.generateNewBundle(toSendDir);
    } else {
      UncompressedPayload.Builder lastSentBundleBuilder = optional.get();
      UncompressedPayload.Builder unprocessedPayloadBuilder = this.generateBundleBuilder();

      String bundleId = "";
      // compare if last sent bundle is same as bundle generated now
      if (BundleUtils.doContentsMatch(unprocessedPayloadBuilder, lastSentBundleBuilder)) {
        bundleId = lastSentBundleBuilder.getBundleId();
        System.out.println("Retransmitting bundle");
      } else {
        try {
          bundleId = this.bundleSecurity.generateNewBundleId();
        } catch (SecurityExceptions.IDGenerationException e) {
          e.printStackTrace();
        } catch (SecurityExceptions.BundleIDCryptographyException e) {
          e.printStackTrace();
        }
      }
      toSend = this.generateNewBundle(unprocessedPayloadBuilder, toSendDir, bundleId);
    }

    Log.d(HelloworldActivity.TAG, "sending bundle with id: " + toSend.getBundleId());
    return toSend;
  }

  public void notifyBundleSent(BundleDTO bundle) {
    FileUtils.deleteQuietly(bundle.getBundle().getSource());
  }

  public BundleSecurity getBundleSecurity()
  {
    return this.bundleSecurity;
  }

  public ClientRouting getClientRouting() { return clientRouting; }

}
