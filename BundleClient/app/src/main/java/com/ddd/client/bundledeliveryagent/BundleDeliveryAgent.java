package com.ddd.client.bundledeliveryagent;

import com.ddd.client.bundletransmission.BundleTransmission;
import com.ddd.model.BundleDTO;
import com.ddd.model.UncompressedPayload;
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
    bundleTransmission = new BundleTransmission(ROOT_FOLDER);
  }

  public BundleDTO send() {
    try {
      System.out.println("[BDA] Starting Bundle Delivery Agent");
      BundleDTO toSend = this.bundleTransmission.generateBundleForTransmission();
      return toSend;
    } catch (Exception e) {
      System.out.println(e);
    }
    return null;
  }
}
