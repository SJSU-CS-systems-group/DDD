package net.discdd.client.bundledeliveryagent;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

import net.discdd.bundlerouting.RoutingExceptions;
import net.discdd.bundlerouting.WindowUtils.WindowExceptions;
import net.discdd.client.bundletransmission.BundleTransmission;
import net.discdd.model.BundleDTO;

import org.whispersystems.libsignal.InvalidKeyException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Logger;

public class BundleDeliveryAgent {

    private static final Logger logger = Logger.getLogger(BundleDeliveryAgent.class.getName());

    private static Path ROOT_FOLDER;
    private static String RelativePath = "/Shared/received-bundles";

    private BundleTransmission bundleTransmission;

    public BundleDeliveryAgent(Path rootFolder) throws WindowExceptions.InvalidLength,
            WindowExceptions.BufferOverflow, RoutingExceptions.ClientMetaDataFileException, IOException,
            NoSuchAlgorithmException, InvalidKeyException {
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
        }
        return null;
    }
}
