package com.ddd.server.api;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ddd.model.BundleDTO;
import com.ddd.model.BundleTransferDTO;
import com.ddd.server.bundletransmission.BundleTransmission;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

@RestController
@RequestMapping("/")
public class TestController {

    @Autowired
    private BundleTransmission bundleTransmission;
    private static final Logger logger = Logger.getLogger(TestController.class.getName());

    String transportId = "transport0";

    @GetMapping("/receive")
    public void testReceive() {
        logger.log(INFO, "Receive flow triggered");
        try {
            this.bundleTransmission.processReceivedBundles(this.transportId);
        } catch (Exception e) {
            logger.log(SEVERE, "Testing ", e);
        }
    }

    @GetMapping("/send")
    public void testSend() {
        Set<String> bundleIdsPresent = new HashSet<>();
        try {
            BundleTransferDTO bundleTransferDTO =
                    this.bundleTransmission.generateBundlesForTransmission(this.transportId, bundleIdsPresent);

            logger.log(WARNING,
                       "[BundleServerApp] Following are the bundle ids that the transport " + this.transportId +
                               " should delete");

            for (String bundleId : bundleTransferDTO.getDeletionSet()) {
                logger.log(FINE, "Test Send ", bundleId);
            }
            logger.log(WARNING, "[BundleServerApp] Following are the ids of the bundles to be sent to transport " +
                    this.transportId);

            for (BundleDTO bundle : bundleTransferDTO.getBundles()) {
                logger.log(FINE, bundle.getBundleId());
            }
        } catch (Exception e) {
            logger.log(SEVERE, "Controller Testing", e);
        }
    }
}
