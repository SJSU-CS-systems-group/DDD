package net.discdd.server.service;

import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import net.discdd.bundlerouting.service.BundleExchangeServiceImpl;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.grpc.BundleSenderType;
import net.discdd.grpc.GetRecencyBlobRequest;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.GrpcService;
import net.discdd.grpc.PublicKeyMap;
import net.discdd.grpc.RecencyBlobStatus;
import net.discdd.server.bundletransmission.BundleTransmission;
import net.discdd.tls.DDDTLSUtil;
import net.discdd.tls.NettyServerCertificateInterceptor;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.ecc.Curve;

import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
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
    public void bundleCompletion(BundleExchangeName bundleExchangeName, BundleSenderType senderType, Path path) {
        X509Certificate clientCert = NettyServerCertificateInterceptor.CLIENT_CERTIFICATE_KEY.get(Context.current());
        logger.log(INFO, "[bundlecompletion] CLIENT CERT " + clientCert.getSubjectX500Principal());
        logger.log(INFO, "Downloaded " + bundleExchangeName.encryptedBundleId());
        bundleTransmission.processBundleFile(path.toFile(), senderType, DDDTLSUtil.publicKeyToName(clientCert.getPublicKey()));
    }

    @Override
    protected void onBundleExchangeEvent(BundleExchangeEvent event) {
        // ignore
    }

    @Override
    public Path pathProducer(BundleExchangeName bundleExchangeName, BundleSenderType senderType, PublicKeyMap publicKeyMap) {
        if (bundleExchangeName.isDownload()) {
            X509Certificate clientCert = NettyServerCertificateInterceptor.CLIENT_CERTIFICATE_KEY.get(Context.current());
            String senderId = "";
            if (publicKeyMap != null){
                var clientIdPubKeyBytes = publicKeyMap.getClientPub();
                try {
                    var clientPubKey = Curve.decodePoint(clientIdPubKeyBytes.toByteArray(), 0);
                    if (!SecurityUtils.verifySignatureRaw(clientCert.getPublicKey().getEncoded(), clientPubKey, publicKeyMap.getSignedTLSPub().toByteArray())) {
                        logger.log(SEVERE, "Problem verifying signature");
                        return null;
                    }
                    senderId = SecurityUtils.generateID(clientPubKey.serialize());
                } catch (InvalidKeyException | NoSuchAlgorithmException e) {
                    logger.log(SEVERE, "Problem verifying signature", e);
                }
            } else {
                senderId = DDDTLSUtil.publicKeyToName(clientCert.getPublicKey());
            }

            logger.log(INFO, "[pathproducer] CLIENT CERT " + clientCert.getSubjectX500Principal());
            logger.log(INFO, senderId + " requested " + bundleExchangeName.encryptedBundleId());

            bundleTransmission.getPathForBundleToSend(bundleExchangeName.encryptedBundleId());
            var bundlePath = bundleTransmission.getPathForBundleToSend(bundleExchangeName.encryptedBundleId());
            if (bundlePath.toFile().exists()) {
                return bundlePath;
            }
            if (senderType != BundleSenderType.CLIENT) {
                return null;
            }
            // let's see if there is something new to send
            try {

                var encryptedBundleId = bundleTransmission.generateBundleForClient(senderId);
                if (encryptedBundleId != null && encryptedBundleId.equals(bundleExchangeName.encryptedBundleId())) {
                    return bundleTransmission.getPathForBundleToSend(encryptedBundleId);
                }
                logger.log(INFO, String.format("%s requested %s but waiting to send %s", senderId,
                                               bundleExchangeName.encryptedBundleId(), encryptedBundleId));
            } catch (Exception e) {
                logger.log(SEVERE, "Problem generating bundle for client " + senderId, e);
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
        X509Certificate clientCert = NettyServerCertificateInterceptor.CLIENT_CERTIFICATE_KEY.get(Context.current());
        String senderId  = DDDTLSUtil.publicKeyToName(clientCert.getPublicKey());
        logger.log(INFO, "[getrecencyblob] CLIENT CERT " + clientCert.getSubjectX500Principal());

        try {
            recencyBlob = bundleTransmission.getRecencyBlob(senderId);
            logger.log(INFO, "Created Blob for sender " + senderId);
        } catch (InvalidKeyException e) {
            recencyBlob =
                    GetRecencyBlobResponse.newBuilder().setStatus(RecencyBlobStatus.RECENCY_BLOB_STATUS_FAILED).build();
            logger.log(SEVERE, "Problem signing recency blob", e);
        }
        responseObserver.onNext(recencyBlob);
        responseObserver.onCompleted();
    }
}
