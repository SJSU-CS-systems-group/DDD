package com.ddd.server;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import com.ddd.model.Bundle;
import com.ddd.model.BundleTransferDTO;
import com.ddd.server.api.BundleServerEndpoint;
import com.ddd.server.config.BundleServerConfig;

@Component
public class StartupRunner implements CommandLineRunner {

  @Autowired private BundleServerEndpoint bundleServerEndpoint;

  @Autowired private BundleServerConfig bundleStoreConfig;

  @Override
  public void run(String... args) throws Exception {

    this.setUpFileStore();
    /* Receive Bundles */
    String transportId = "transport-0";
    this.bundleServerEndpoint.receiveBundles(transportId);

    /* Send Bundles */
    Set<String> bundleIdsPresent = new HashSet<>();
    BundleTransferDTO bundleTransferDTO =
        this.bundleServerEndpoint.generateBundles(transportId, bundleIdsPresent);

    System.out.println(
        "[BundleServerApp] Following are the bundle ids that the transport "
            + transportId
            + " should delete");

    for (String bundleId : bundleTransferDTO.getDeletionSet()) {
      System.out.println(bundleId);
    }
    System.out.println(
        "[BundleServerApp] Following are the ids of the bundles to be sent to transport "
            + transportId);

    for (Bundle bundle : bundleTransferDTO.getBundles()) {
      System.out.println(bundle.getBundleId());
    }
  }

  private void setUpFileStore() {
    File bundleReceivedDir =
        new File(this.bundleStoreConfig.getBundleTransmission().getBundleReceivedLocation());
    bundleReceivedDir.mkdirs();
    File bundleGenerationDir =
        new File(this.bundleStoreConfig.getBundleTransmission().getBundleGenerationDirectory());
    bundleGenerationDir.mkdirs();
    File toBeBundledDir =
        new File(this.bundleStoreConfig.getBundleTransmission().getToBeBundledDirectory());
    toBeBundledDir.mkdirs();
    File tosendDir = new File(this.bundleStoreConfig.getBundleTransmission().getToSendDirectory());
    tosendDir.mkdirs();
    
    File dbRoot = new File(this.bundleStoreConfig.getDbRoot());
    dbRoot.mkdirs();
    File sentBundleDetails = new File(bundleStoreConfig.getApplicationDataManager().getStateManager().getSentBundleDetails());
    try {
      sentBundleDetails.createNewFile();
      File largestADUIdReceived = new File(bundleStoreConfig.getApplicationDataManager().getStateManager().getLargestAduIdReceived());
      largestADUIdReceived.createNewFile();
      File largestADUIdDelivered = new File(bundleStoreConfig.getApplicationDataManager().getStateManager().getLargestAduIdDelivered());
      largestADUIdDelivered.createNewFile();
      File lastSentBundleStructure = new File(bundleStoreConfig.getApplicationDataManager().getStateManager().getLastSentBundleStructure());
      lastSentBundleStructure.createNewFile();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
