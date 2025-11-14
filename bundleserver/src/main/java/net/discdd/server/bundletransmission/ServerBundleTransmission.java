package net.discdd.server.bundletransmission;

import com.google.protobuf.ByteString;
import net.discdd.bundlerouting.RoutingExceptions.ClientMetaDataFileException;
import net.discdd.bundlesecurity.BundleIDGenerator;
import net.discdd.bundlesecurity.DDDPEMEncoder;
import net.discdd.bundlesecurity.InvalidClientIDException;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.bundlesecurity.ServerSecurity;
import net.discdd.grpc.BundleSenderType;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.RecencyBlob;
import net.discdd.grpc.RecencyBlobStatus;
import net.discdd.model.Bundle;
import net.discdd.model.Payload;
import net.discdd.model.UncompressedBundle;
import net.discdd.model.UncompressedPayload;
import net.discdd.server.applicationdatamanager.ServerApplicationDataManager;
import net.discdd.server.bundlerouting.BundleRouting;
import net.discdd.server.bundlerouting.ServerWindowService;
import net.discdd.server.bundlesecurity.ServerBundleSecurity;
import net.discdd.server.config.BundleServerConfig;
import net.discdd.server.repository.entity.ClientBundleCounters;
import net.discdd.utils.BundleUtils;
import net.discdd.utils.FileUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidMessageException;

import java.io.File;
import java.io.IOException;
import java.io.PipedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;
import static net.discdd.bundlesecurity.SecurityUtils.generateID;
import static net.discdd.grpc.BundleSenderType.CLIENT;

@EnableAsync
@Service
public class ServerBundleTransmission {

    private static final Logger logger = Logger.getLogger(ServerBundleTransmission.class.getName());
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    public static final int WINDOW_LENGTH = 3;
    private final BundleServerConfig config;
    private final ServerBundleSecurity bundleSecurity;
    private final ServerApplicationDataManager applicationDataManager;
    private final BundleRouting bundleRouting;
    private final ServerWindowService serverWindowService;
    private final ServerSecurity serverSecurity;
    SecureRandom secureRandom = new SecureRandom();

    public ServerBundleTransmission(ServerBundleSecurity bundleSecurity,
                                    ServerApplicationDataManager applicationDataManager,
                                    BundleRouting bundleRouting,
                                    BundleServerConfig config,
                                    ServerWindowService serverWindowService,
                                    ServerSecurity serverSecurity) {
        this.config = config;
        this.bundleSecurity = bundleSecurity;
        this.applicationDataManager = applicationDataManager;
        this.bundleRouting = bundleRouting;
        this.serverWindowService = serverWindowService;
        this.serverSecurity = serverSecurity;
    }

    public static String bundleSenderToString(BundleSenderType senderType, String senderId) {
        return senderType + " : " + senderId;
    }

    private static Random random = new Random();

    private String nextRandomString() {
        return Long.toHexString(random.nextLong());
    }

    @Async
    @Transactional
    public void processReceivedBundle(BundleSenderType senderType, String senderId, Bundle bundle) throws Exception {
        logger.log(INFO,
                   "Processing received bundle: " + bundle.getSource().getName() + " from " +
                           bundleSenderToString(senderType, senderId));
        if (!bundle.getSource().exists() || bundle.getSource().length() == 0) {
            return;
        }

        Path bundleRecvProcDir =
                this.config.getBundleTransmission().getReceivedProcessingDirectory().resolve(nextRandomString());
        try {
            Files.createDirectories(bundleRecvProcDir);

            UncompressedBundle uncompressedBundle = BundleUtils.extractBundle(bundle, bundleRecvProcDir);
            String serverIdReceived =
                    generateID(uncompressedBundle.getSource().toPath().resolve(SecurityUtils.SERVER_IDENTITY_KEY));
            if (!bundleSecurity.bundleServerIdMatchesCurrentServer(serverIdReceived)) {
                logger.log(WARNING,
                           "Received bundle's serverIdentity didn't match with current server, " +
                                   "ignoring bundle with bundleId: " + uncompressedBundle.getBundleId());
                return;
            }

            String clientIdBase64 = DDDPEMEncoder.decodeEncryptedPublicKeyfromFile(serverSecurity.getSigningKey(),
                                                                                   uncompressedBundle.getSource()
                                                                                           .toPath()
                                                                                           .resolve(SecurityUtils.CLIENT_IDENTITY_KEY));
            String clientId = generateID(clientIdBase64);
            var counters = this.applicationDataManager.getBundleCountersForClient(clientId);

            var receivedBundleCounter =
                    this.bundleSecurity.getCounterFromBundlePath(uncompressedBundle.getSource().toPath(),
                                                                 BundleIDGenerator.UPSTREAM);

            if (receivedBundleCounter <= counters.lastReceivedBundleCounter) {
                logger.log(WARNING,
                           "[BundleTransmission] Skipping bundle " + bundle.getSource().getName() +
                                   " already received");
                return;
            }

            Payload payload = this.bundleSecurity.decryptPayload(uncompressedBundle);
            if (payload == null) {
                throw new Exception("Payload is null");
            }

            UncompressedPayload uncompressedPayload =
                    BundleUtils.extractPayload(payload, uncompressedBundle.getSource().toPath());
            logger.log(FINE, "[BundleTransmission] extracted payload from uncompressed bundle");

            if (!"HB".equals(uncompressedPayload.getAckRecord().getBundleId())) {
                this.serverWindowService.processACK(clientId, uncompressedPayload.getAckRecord().getBundleId());
            }

            try {
                this.bundleRouting.processClientMetaData(uncompressedPayload.getSource().toPath(), senderId, clientId);
            } catch (ClientMetaDataFileException | SQLException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

            this.applicationDataManager.processAcknowledgement(clientId,
                                                               uncompressedPayload.getAckRecord().getBundleId());
            if (!uncompressedPayload.getADUs().isEmpty()) {
                this.applicationDataManager.storeReceivedADUs(clientId,
                                                              uncompressedPayload.getBundleId(),
                                                              receivedBundleCounter,
                                                              uncompressedPayload.getADUs());
            }
        } finally {
            FileUtils.recursiveDelete(bundleRecvProcDir);
        }
    }

    public void processBundleFile(File bundleFile, BundleSenderType senderType, String senderId) {
        Bundle bundle = new Bundle(bundleFile);
        try {
            this.processReceivedBundle(senderType, senderId, bundle);
        } catch (Exception e) {
            logger.log(SEVERE,
                       "[BundleTransmission] Failed to process received bundle from: " +
                               bundleSenderToString(senderType, senderId),
                       e);
        } finally {
            FileUtils.recursiveDelete(bundle.getSource().toPath());
        }
    }

    public String generateBundleId(String clientId) {
        if (this.serverWindowService.generateNewBundleCounter(clientId) <= 0) {
            logger.log(INFO, "Server's window is full for the client " + clientId);
        }
        return this.applicationDataManager.getBundleCountersForClient(clientId).lastSentBundleId;
    }

    public String generateBundleForClient(String clientId) throws InvalidClientIDException, GeneralSecurityException,
            InvalidKeyException, IOException {
        logger.log(INFO, "[BundleTransmission] Processing bundle generation request for client " + clientId);

        ClientBundleCounters bundleCountersForClient = this.applicationDataManager.getBundleCountersForClient(clientId);
        if (this.serverWindowService.isWindowFull(clientId)) {
            logger.log(INFO,
                       "Server's window is full for the client " + clientId + " returning last sent bundle ID " +
                               bundleCountersForClient.lastSentBundleId);
            return bundleCountersForClient.lastSentBundleId;
        }

        var counts = bundleCountersForClient;
        if (counts.lastSentBundleCounter > 0 && !applicationDataManager.newDataToSend(counts.lastSentBundleId) &&
                !applicationDataManager.newAckNeeded(counts.lastSentBundleId)) {
            // Nothing new to send, so lets send the last bundle again.
            return counts.lastSentBundleId;
        }

        var bundleCounter = counts.lastSentBundleCounter + 1;
        var encryptedBundleId =
                serverSecurity.createEncryptedBundleId(clientId, bundleCounter, BundleIDGenerator.DOWNSTREAM);
        long ackedRecievedBundle = counts.lastReceivedBundleCounter;
        applicationDataManager.registerNewBundleId(clientId, encryptedBundleId, bundleCounter, ackedRecievedBundle);

        var adus = applicationDataManager.fetchADUsToSend(encryptedBundleId, bundleCounter, 0, clientId);
        PipedInputStream pipedInputStream = new PipedInputStream();
        Future<?> future =
                BundleUtils.runFuture(executorService, counts.lastReceivedBundleId, null, adus, null, pipedInputStream);
        try {
            var bundleOutputStream = Files.newOutputStream(getPathForBundleToSend(encryptedBundleId),
                                                           StandardOpenOption.CREATE,
                                                           StandardOpenOption.TRUNCATE_EXISTING);
            BundleUtils.encryptPayloadAndCreateBundle((inputStream, outputStream) -> serverSecurity.encrypt(clientId,
                                                                                                            inputStream,
                                                                                                            outputStream),
                                                      serverSecurity.getClientIdentityPublicKey(clientId),
                                                      serverSecurity.getClientBaseKey(clientId),
                                                      serverSecurity.getIdentityPublicKey().getPublicKey(),
                                                      encryptedBundleId,
                                                      pipedInputStream,
                                                      bundleOutputStream);

        } catch (InvalidMessageException e) {
            throw new GeneralSecurityException(e);
        } finally {
            future.cancel(true);
        }
        logger.log(INFO,
                   "Bundle generated for client " + clientId + " with ID: " + encryptedBundleId + " in " +
                           getPathForBundleToSend(encryptedBundleId));
        return encryptedBundleId;
    }

    public GetRecencyBlobResponse getRecencyBlob(String senderId) throws InvalidKeyException {
        var blob = RecencyBlob.newBuilder()
                .setVersion(0)
                .setNonce(secureRandom.nextInt())
                .setBlobTimestamp(System.currentTimeMillis())
                .setSenderId(senderId)
                .build();
        byte[] signature = this.bundleSecurity.signRecencyBlob(blob);
        byte[] publicKeyBytes = this.bundleSecurity.getIdentityPublicKey();
        return GetRecencyBlobResponse.newBuilder()
                .setStatus(RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS)
                .setRecencyBlob(blob)
                .setRecencyBlobSignature(ByteString.copyFrom(signature))
                .setServerPublicKey(ByteString.copyFrom(publicKeyBytes))
                .build();
    }

    public Path getPathForBundleToSend(String encryptedBundleId) {
        return getPathToSendDirectory().resolve(encryptedBundleId);
    }

    public Path getPathToSendDirectory() {
        return config.getBundleTransmission().getToSendDirectory();
    }

    public Path getPathForBundleToReceive(String randomBundleId) {
        return getPathForBundleReceiveDirectory().resolve(randomBundleId);
    }

    private Path getPathForBundleReceiveDirectory() {
        return config.getBundleTransmission().getBundleReceivedLocation();
    }

    public BundlesToExchange inventoryBundlesForTransmission(BundleSenderType senderType,
                                                             String senderId,
                                                             Set<String> bundleIdsPresent) {
        List<String> clientIds = CLIENT == senderType ?
                                 Collections.singletonList(senderId) :
                                 this.bundleRouting.getClientsForTransportId(senderId);

        logger.log(SEVERE,
                   "[BundleTransmission] Found " + clientIds.size() + " reachable from the sender: " +
                           bundleSenderToString(senderType, senderId));
        Set<String> deletionSet = new HashSet<>(bundleIdsPresent);
        List<String> bundlesToSend = new ArrayList<>();

        for (String clientId : clientIds) {
            try {
                var clientBundle = this.generateBundleForClient(clientId);
                bundlesToSend.add(clientBundle);
            } catch (InvalidClientIDException | GeneralSecurityException | InvalidKeyException | IOException e) {
                logger.log(SEVERE, "Failed to generate bundle for client " + clientId, e);
            }
        }
        bundlesToSend.forEach(deletionSet::remove);

        // the bundleIdsPresent.stream().toList() doesn't actually contain the bundlesToUpload
        // in this method it's just as a placeholder, because we don't have the bundlesToUpload here
        // look at net.discdd.server.service.BundleServerServiceImpl.bundleInventory to see how the return object is
        // used
        return new BundlesToExchange(bundlesToSend, bundleIdsPresent.stream().toList(), new ArrayList<>(deletionSet));
    }

    public record BundlesToExchange(List<String> bundlesToDownload,
                                    List<String> bundlesToUpload,
                                    List<String> bundlesToDelete) {}
}
