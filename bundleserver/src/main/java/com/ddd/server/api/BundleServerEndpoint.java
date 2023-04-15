package com.ddd.server.api;

import java.util.Set;
import org.springframework.stereotype.Component;
import com.ddd.model.BundleTransferDTO;
import com.ddd.server.bundletransmission.BundleTransmission;

@Component
public class BundleServerEndpoint {

  private BundleTransmission bundleTransmission;

  public BundleServerEndpoint(BundleTransmission bundleTransmission) {
    this.bundleTransmission = bundleTransmission;
  }

  public void receiveBundles(String transportId) {
    System.out.println(
        "[BSE] Started execution of bundle server endpoint for receiving bundles flow");
    this.bundleTransmission.processReceivedBundles(transportId);
  }

  public BundleTransferDTO generateBundles(String transportId, Set<String> bundleIdsPresent) {
    System.out.println(
        "[BSE] Started execution of bundle server endpoint for generating bundles flow");
    BundleTransferDTO ret =
        this.bundleTransmission.generateBundlesForTransmission(transportId, bundleIdsPresent);
    this.bundleTransmission.notifyBundleSent(ret.getBundles());
    return ret;
  }
}
