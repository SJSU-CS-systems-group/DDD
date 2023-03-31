package com.ddd.server.api;

import java.util.Set;
import com.ddd.model.BundleTransferDTO;
import com.ddd.server.bundletransmission.BundleTransmission;

public class BundleServerEndpoint {

  private BundleTransmission bundleTransmission;

  public BundleServerEndpoint() {
    this.bundleTransmission = new BundleTransmission();
  }

  public void receiveBundles() {
    System.out.println(
        "[BSE] Started execution of bundle server endpoint for receiving bundles flow");
    // this.bundleTransmission.processReceivedBundles();
  }

  public BundleTransferDTO generateBundles(String transportId, Set<String> bundleIdsPresent) {
    System.out.println(
        "[BSE] Started execution of bundle server endpoint for generating bundles flow");
    BundleTransferDTO ret =
        this.bundleTransmission.generateBundlesForTransmission(transportId, bundleIdsPresent);
    this.bundleTransmission.deleteSentBundles(ret.getBundles());
    return ret;
  }
}
