package com.ddd.client.bundledeliveryagent;

import com.ddd.client.bundletransmission.BundleTransmission;
import com.ddd.model.BundleDTO;
import com.ddd.model.UncompressedPayload;
import com.ddd.model.BundleWrapper;

import java.util.logging.Logger;

import static java.util.logging.Level.FINER;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Level.SEVERE;

import java.io.File;

public class BundleDeliveryAgent {

    private static final Logger logger = Logger.getLogger(BundleDeliveryAgent.class.getName());

    private static String ROOT_FOLDER;
    private static String RelativePath = "/Shared/received-bundles";

    private BundleTransmission bundleTransmission;

    public BundleDeliveryAgent(String rootFolder) {
        ROOT_FOLDER = rootFolder;
        File receivedBundlesDir = new File(ROOT_FOLDER + RelativePath);
        receivedBundlesDir.mkdirs();
        bundleTransmission = new BundleTransmission(ROOT_FOLDER);
    }

    public BundleDTO send() {
        try {
            logger.log(INFO, "[BDA] Starting Bundle Delivery Agent");
            BundleDTO toSend = this.bundleTransmission.generateBundleForTransmission();
            return toSend;
        } catch (Exception e) {
            logger.log(WARNING, "Error: ", e);
        } return null;
    }
}
