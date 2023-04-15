package com.ddd.server.api;

import java.util.Set;
import org.springframework.stereotype.Component;
import com.ddd.model.BundleDTO;
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
        "[BSE] Following are the bundle ids that are present on the bundle transport");
    for (String bundleIdPresent : bundleIdsPresent) {
      System.out.println(bundleIdPresent);
    }
    System.out.println(
        "[BSE] Started execution of bundle server endpoint for generating bundles flow");
    BundleTransferDTO ret =
        this.bundleTransmission.generateBundlesForTransmission(transportId, bundleIdsPresent);
    for (BundleDTO bundleDTO : ret.getBundles()) {
      this.bundleTransmission.notifyBundleSent(bundleDTO.getBundle());
    }
    return ret;
  }
}
