package com.ddd.client.bundledeliveryagent;

import com.ddd.client.bundletransmission.BundleTransmission;
import com.ddd.model.Bundle;
import com.ddd.model.BundleWrapper;

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

  public BundleWrapper send() {
    System.out.println("[BDA] Starting Bundle Delivery Agent");
    this.bundleTransmission = new BundleTransmission(ROOT_FOLDER);
//    this.bundleTransmission.processReceivedBundles(ROOT_FOLDER + RelativePath);
    BundleWrapper toSend = this.bundleTransmission.generateBundleForTransmission();
    return toSend;
//    this.bundleTransmission.notifyBundleSent(toSend);
//    System.out.println("[BDA] An outbound bundle generated with id: " + toSend.getBundleId());
  }
}
