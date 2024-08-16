package net.discdd.server.service;

import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import net.discdd.bundlerouting.service.BundleExchangeServiceImpl;
import net.discdd.bundlesecurity.BundleIDGenerator;
import net.discdd.grpc.BundleSender;
import net.discdd.grpc.BundleSenderType;
import net.discdd.grpc.GetRecencyBlobRequest;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.RecencyBlobStatus;
import net.discdd.server.bundletransmission.BundleTransmission;
import org.springframework.beans.factory.annotation.Value;
import org.whispersystems.libsignal.InvalidKeyException;

import javax.annotation.PostConstruct;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

@GrpcService
public class BundleServerExchangeServiceImpl extends BundleExchangeServiceImpl {
    private static final Logger logger = Logger.getLogger(BundleServerExchangeServiceImpl.class.getName());
    private final String serverBasePath;
    private final BundleTransmission bundleTransmission;
    Random random = new Random();
    private Path downloadingFrom;
    private Path uploadingTo;

    public BundleServerExchangeServiceImpl(@Value("${bundle-server.bundle-store-shared}")
                                           String serverBasePath, BundleTransmission bundleTransmission) {
        this.serverBasePath = serverBasePath;
        this.bundleTransmission = bundleTransmission;
    }

    @PostConstruct
    private void init() {
        logger.log(Level.INFO, "inside ClientFileServiceImpl init method");
        var basePath = Path.of(serverBasePath);
        this.downloadingFrom = basePath.resolve("send");
        downloadingFrom.toFile().mkdirs();
        logger.log(INFO, "Downloading from: " + downloadingFrom + " created");
        this.uploadingTo = basePath.resolve("receive");
        uploadingTo.toFile().mkdirs();
        logger.log(INFO, "Uploading to: " + uploadingTo + " created");
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
            if (bundlePath.toFile().exists()) return bundlePath;

            // we only generate bundles on the fly here for clients
            if (sender.getType() != BundleSenderType.CLIENT) return null;

            return getPathForClientBundleDownload(bundleExchangeName.encryptedBundleId(), sender);
        } else {
            byte[] randomBytes = new byte[16];
            random.nextBytes(randomBytes);
            // we want to produce a random path so that malicious clients can't mess things up
            return uploadingTo.resolve(Base64.getUrlEncoder().encodeToString(randomBytes));
        }
    }

    private Path getPathForClientBundleDownload(String requestedEncryptedBundleId, BundleSender sender) {

        try {
            var currentBundleIdForClient = bundleTransmission.generateBundleId(sender.getId());
            if (!currentBundleIdForClient.equals(requestedEncryptedBundleId)) {
                logger.log(WARNING, String.format("Client %s requested %s (%d) but the next id is %s (%d)",
                                                  BundleTransmission.bundleSenderToString(sender),
                                                  requestedEncryptedBundleId,
                                                  bundleTransmission.getCounterFromEncryptedBundleId(requestedEncryptedBundleId, sender.getId(), BundleIDGenerator.DOWNSTREAM),
                                                  currentBundleIdForClient,
                                                  bundleTransmission.getCounterFromEncryptedBundleId(currentBundleIdForClient, sender.getId(),
                                                                                                     BundleIDGenerator.DOWNSTREAM)));
                return null;
            }
            var bundles = bundleTransmission.generateBundleForTransmission(sender, sender.getId(), null);
            if (bundles.getBundles().size() != 1) {
                logger.log(WARNING, "Couldn't generated current bundle " + requestedEncryptedBundleId + " for " +
                                   BundleTransmission.bundleSenderToString(sender));
                return null;
            }
            var bundle = bundles.getBundles().get(0);
            String generatedBundleId = bundle.getBundleId();
            if (!generatedBundleId.equals(requestedEncryptedBundleId)) {
                logger.log(WARNING,
                           BundleTransmission.bundleSenderToString(sender) + " requested " + requestedEncryptedBundleId +
                                   " but generated " + generatedBundleId);
                return null;
            }
            return bundle.getBundle().getSource().toPath();
        } catch (Exception e) {
            logger.log(SEVERE, "Error generating bundle for transmission to " + sender, e);
        }
        return null;
    }

    @Override
    public void getRecencyBlob(GetRecencyBlobRequest request, StreamObserver<GetRecencyBlobResponse> responseObserver) {
        GetRecencyBlobResponse recencyBlob = null;
        try {
            recencyBlob = bundleTransmission.getRecencyBlob();
        } catch (InvalidKeyException e) {
            recencyBlob =
                    GetRecencyBlobResponse.newBuilder().setStatus(RecencyBlobStatus.RECENCY_BLOB_STATUS_FAILED).build();
            logger.log(SEVERE, "Problem signing recency blob", e);
        }
        responseObserver.onNext(recencyBlob);
        responseObserver.onCompleted();
    }
}
