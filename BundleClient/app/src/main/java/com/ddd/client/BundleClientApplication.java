package com.ddd.client;

import com.ddd.client.bundledeliveryagent.BundleDeliveryAgent;

import java.nio.file.Paths;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;

public class BundleClientApplication {
    private static final Logger logger = Logger.getLogger(BundleClientApplication.class.getName());

    private static BundleDeliveryAgent bundleDeliveryAgent;

    static {
        try {
            bundleDeliveryAgent = new BundleDeliveryAgent(Paths.get("."));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        logger.log(INFO, "Starting Bundle Client!");
        bundleDeliveryAgent.send();
    }
}
