package net.discdd.server.service;

import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import net.discdd.bundlerouting.service.BundleExchangeServiceImpl;
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

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

@GrpcService
public class BundleServerExchangeServiceImpl extends BundleExchangeServiceImpl {
    private static final Logger logger = Logger.getLogger(BundleServerExchangeServiceImpl.class.getName());
    private final BundleTransmission bundleTransmission;
    Random random = new Random();
    private Path downloadingFrom;
    private Path uploadingTo;

    public BundleServerExchangeServiceImpl(BundleTransmission bundleTransmission) {
        this.bundleTransmission = bundleTransmission;
    }

    @Override
    public void bundleCompletion(BundleExchangeName bundleExchangeName, BundleSender sender) {
        bundleTransmission.processReceivedBundles(sender);
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
            if (bundlePath.toFile().exists()) {
                return bundlePath;
            }
            if (sender.getType() != BundleSenderType.CLIENT) {
                return null;
            }
            // let's see if there is something new to send
            try {
                var encryptedBundleId = bundleTransmission.generateBundleForClient(sender.getId());
                if (encryptedBundleId != null && encryptedBundleId.equals(bundleExchangeName.encryptedBundleId())) {
                    return bundleTransmission.getPathForBundleToSend(encryptedBundleId);
                }
                logger.log(INFO, String.format("%s requested %s but waiting to send %s", sender.getId(),
                                               bundleExchangeName.encryptedBundleId(), encryptedBundleId));
            } catch (Exception e) {
                logger.log(SEVERE, "Problem generating bundle for client " + sender.getId(), e);
            }
            return null;
        } else {
            byte[] randomBytes = new byte[16];
            random.nextBytes(randomBytes);
            // we want to produce a random path so that malicious clients can't mess things up
            return bundleTransmission.getPathForBundleToReceive(Base64.getUrlEncoder().encodeToString(randomBytes));
        }
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
