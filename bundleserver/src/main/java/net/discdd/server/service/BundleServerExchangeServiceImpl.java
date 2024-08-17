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
import org.whispersystems.libsignal.InvalidKeyException;

import java.nio.file.Path;
import java.util.Base64;
import java.util.Random;
import java.util.logging.Logger;

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

    public BundleServerExchangeServiceImpl(BundleTransmission bundleTransmission) {
        this.bundleTransmission = bundleTransmission;
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
            bundleTransmission.getPathForBundleToSend(bundleExchangeName.encryptedBundleId());
            var bundlePath = bundleTransmission.getPathForBundleToSend(bundleExchangeName.encryptedBundleId());
            if (bundlePath.toFile().exists()) return bundlePath;

            // we only generate bundles on the fly here for clients
            if (sender.getType() != BundleSenderType.CLIENT) return null;

            return getPathForClientBundleDownload(bundleExchangeName.encryptedBundleId(), sender);
        } else {
            byte[] randomBytes = new byte[16];
            random.nextBytes(randomBytes);
            // we want to produce a random path so that malicious clients can't mess things up
            return bundleTransmission.getPathForBundleToReceive(Base64.getUrlEncoder().encodeToString(randomBytes));
        }
    }

    private Path getPathForClientBundleDownload(String requestedEncryptedBundleId, BundleSender sender) {

        try {
            String expectedBundleId = bundleTransmission.generateBundleId(sender.getId());
            String requestedBundleId = bundleExchangeName.encryptedBundleId();
            var generatedBundleId = bundleTransmission.generateBundleForClient(sender, sender.getId());
            if (generatedBundleId == null) {
                logger.log(WARNING,
                           BundleTransmission.bundleSenderToString(sender) + " requested " + requestedBundleId +
                                   " but nothing was generated");
                return null;
            }
            if (!generatedBundleId.equals(requestedBundleId)) {
                logger.log(WARNING,
                           BundleTransmission.bundleSenderToString(sender) + " requested " + requestedEncryptedBundleId +
                                   " but generated " + generatedBundleId);
                return null;
            }
            return bundleTransmission.getPathForBundleToSend(generatedBundleId);
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
