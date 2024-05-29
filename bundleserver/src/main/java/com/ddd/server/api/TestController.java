package com.ddd.server.api;

import java.util.HashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ddd.model.BundleDTO;
import com.ddd.model.BundleTransferDTO;
import com.ddd.server.bundletransmission.BundleTransmission;

@RestController
@RequestMapping("/")
public class TestController {

    @Autowired
    private BundleTransmission bundleTransmission;

    String transportId = "transport0";

    @GetMapping("/receive")
    public void testReceive() {
        System.out.println("Receive flow triggered");
        try {
            this.bundleTransmission.processReceivedBundles(this.transportId);
        } catch (Exception e) {
            System.out.println(e);
        }
    }

    @GetMapping("/send")
    public void testSend() {
        Set<String> bundleIdsPresent = new HashSet<>();
        try {
            BundleTransferDTO bundleTransferDTO =
                    this.bundleTransmission.generateBundlesForTransmission(this.transportId, bundleIdsPresent);

            System.out.println("[BundleServerApp] Following are the bundle ids that the transport " + this.transportId +
                                       " should delete");

            for (String bundleId : bundleTransferDTO.getDeletionSet()) {
                System.out.println(bundleId);
            }
            System.out.println("[BundleServerApp] Following are the ids of the bundles to be sent to transport " +
                                       this.transportId);

            for (BundleDTO bundle : bundleTransferDTO.getBundles()) {
                System.out.println(bundle.getBundleId());
            }
        } catch (Exception e) {
            System.out.println(e);
        }
    }
}
