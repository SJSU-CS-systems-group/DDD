package com.ddd.client.bundledeliveryagent;

import com.ddd.client.bundletransmission.BundleTransmission;
import com.ddd.model.Bundle;

import java.io.File;

public class BundleDeliveryAgent {
  private static String ROOT_FOLDER;
  private static String RelativePath = "/Shared/received-bundles";

  private BundleTransmission bundleTransmission;

  public BundleDeliveryAgent(String rootFolder){
    ROOT_FOLDER = rootFolder;
    File receivedBundlesDir = new File(ROOT_FOLDER+RelativePath);
    receivedBundlesDir.mkdirs();
  }

  public void start() {
    System.out.println("[BDA] Starting Bundle Delivery Agent");
    this.bundleTransmission = new BundleTransmission(ROOT_FOLDER);
    this.bundleTransmission.processReceivedBundles(ROOT_FOLDER + RelativePath);
//    Bundle toSend = this.bundleTransmission.generateBundleForTransmission();
//    this.bundleTransmission.notifyBundleSent(toSend);
//    System.out.println("[BDA] An outbound bundle generated with id: " + toSend.getBundleId());
  }
}
