package com.ddd.client;

import com.ddd.client.bundledeliveryagent.BundleDeliveryAgent;

public class BundleClientApplication {

  private static BundleDeliveryAgent bundleDeliveryAgent = new BundleDeliveryAgent();

  public static void main(String[] args) {
    System.out.println("Starting Bundle Client!");
    bundleDeliveryAgent.start();
  }
}
