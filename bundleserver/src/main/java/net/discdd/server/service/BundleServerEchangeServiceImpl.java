package net.discdd.server.service;

import net.devh.boot.grpc.server.service.GrpcService;
import net.discdd.bundlerouting.service.BundleExchangeServiceImpl;
import net.discdd.grpc.BundleSender;
import net.discdd.grpc.BundleSenderType;
import net.discdd.server.bundletransmission.BundleTransmission;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.PostConstruct;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;

@GrpcService
public class BundleServerEchangeServiceImpl extends BundleExchangeServiceImpl {
    private final String serverBasePath;
    private final BundleTransmission bundleTransmission;
    private static final Logger logger = Logger.getLogger(BundleServerEchangeServiceImpl.class.getName());
    private Path downloadingFrom;
    private Path uploadingTo;

    public BundleServerEchangeServiceImpl(@Value("${bundle-server.bundle-store-shared}") String serverBasePath, BundleTransmission bundleTransmission) {
        this.serverBasePath = serverBasePath;
        this.bundleTransmission = bundleTransmission;
    }
    @PostConstruct
    private void init() {
        logger.log(Level.INFO, "inside ClientFileServiceImpl init method");
        var basePath = Path.of(serverBasePath);
        this.downloadingFrom = basePath.resolve("send");
        this.uploadingTo = basePath.resolve("receive");
    }

    @Override
    public void bundleCompletion(BundleExchangeName bundleExchangeName) {
        bundleTransmission.processReceivedBundles(
                BundleSender.newBuilder().setType(BundleSenderType.CLIENT).setId("unknown").build());
    }

    @Override
    protected void onBundleExchangeEvent(BundleExchangeEvent event) {
        // ignore
    }

    @Override
    public Path pathProducer(BundleExchangeName bundleExchangeName, BundleSender sender) {
        if (bundleExchangeName.isDownload()) {
            var bundlePath = downloadingFrom.resolve(bundleExchangeName.encryptedBundleId());
            if (bundlePath.toFile().exists()) {
                return bundlePath;
            }

            if (sender.getType() != BundleSenderType.CLIENT) {
                // we only generate bundles on the fly here for clients
                return null;
            }

            try {
                var bundles = bundleTransmission.generateBundleForTransmission(null, sender.getId(), null);
                if (bundles.getBundles().size() != 1) throw new Exception("Only one bundle expected to send to client");
                var bundle = bundles.getBundles().get(0);
                String generatedBundleId = bundle.getBundleId();
                String requestedBundleId = bundleExchangeName.encryptedBundleId();
                if (!generatedBundleId.equals(requestedBundleId)) {
                    throw new Exception("Requested " + requestedBundleId + " but generated " + generatedBundleId);
                }
                return bundle.getBundle().getSource().toPath();
            } catch (Exception e) {
                logger.log(SEVERE, "Error generating bundle for transmission to " + sender, e);
            }
            return bundlePath;
        } else {
            return uploadingTo.resolve(bundleExchangeName.encryptedBundleId());
        }
    }
}
