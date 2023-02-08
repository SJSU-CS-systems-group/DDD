package com.ddd.client.bundledeliveryagent;

import com.ddd.client.bundletransmission.BundleTransmission;
import com.ddd.model.Bundle;

public class BundleDeliveryAgent {

  private static final String BUNDLE_RECEIVED_LOCATION =
      "C:\\Masters\\CS 297-298\\CS 298\\Implementation\\AppStorage\\Client\\Shared\\received-bundles";

  private BundleTransmission bundleTransmission;

  public void start() {
    System.out.println("[BDA] Starting Bundle Delivery Agent");
    this.bundleTransmission = new BundleTransmission();
    this.bundleTransmission.processReceivedBundles(BUNDLE_RECEIVED_LOCATION);
    Bundle toSend = this.bundleTransmission.generateBundleForTransmission();
    this.bundleTransmission.deleteSentBundle(toSend);
    System.out.println("[BDA] An outbound bundle generated with id: " + toSend.getBundleId());
  }
}
