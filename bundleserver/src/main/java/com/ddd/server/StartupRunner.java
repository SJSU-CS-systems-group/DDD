package com.ddd.server;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import com.ddd.model.BundleDTO;
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
    String transportId = "transport0";
    //    this.bundleServerEndpoint.receiveBundles(transportId);

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

    for (BundleDTO bundle : bundleTransferDTO.getBundles()) {
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
    File receivedProcessingDir =
        new File(this.bundleStoreConfig.getBundleTransmission().getReceivedProcessingDirectory());
    receivedProcessingDir.mkdirs();

    File unCompressedPayloadDir =
        new File(this.bundleStoreConfig.getBundleTransmission().getUncompressedPayloadDirectory());
    unCompressedPayloadDir.mkdirs();
    File compressedPayloadDir =
        new File(this.bundleStoreConfig.getBundleTransmission().getCompressedPayloadDirectory());
    compressedPayloadDir.mkdirs();
    File encryptedPayloadDir =
        new File(this.bundleStoreConfig.getBundleTransmission().getEncryptedPayloadDirectory());
    encryptedPayloadDir.mkdirs();
  }
}
