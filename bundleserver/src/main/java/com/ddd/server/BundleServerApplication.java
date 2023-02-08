package com.ddd.server;

import java.util.HashSet;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.ddd.model.Bundle;
import com.ddd.model.BundleTransferDTO;
import com.ddd.server.api.BundleServerEndpoint;

@SpringBootApplication
public class BundleServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(BundleServerApplication.class, args);

    /* Receive Bundles */
    BundleServerEndpoint bundleServerEndpoint = new BundleServerEndpoint();
    bundleServerEndpoint.receiveBundles();

    /* Send Bundles */
    Set<String> bundleIdsPresent = new HashSet<>();
    String transportId = "transport-0";
    BundleTransferDTO bundleTransferDTO =
        bundleServerEndpoint.generateBundles(transportId, bundleIdsPresent);

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
}
