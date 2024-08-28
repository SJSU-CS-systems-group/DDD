package net.discdd.server.bundletransmission;

import com.google.protobuf.ByteString;
import net.discdd.bundlerouting.RoutingExceptions.ClientMetaDataFileException;
import net.discdd.bundlesecurity.BundleIDGenerator;
import net.discdd.bundlesecurity.InvalidClientIDException;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.bundlesecurity.ServerSecurity;
import net.discdd.grpc.BundleSender;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.RecencyBlob;
import net.discdd.grpc.RecencyBlobStatus;
import net.discdd.model.Acknowledgement;
import net.discdd.model.Bundle;
import net.discdd.model.Payload;
import net.discdd.model.UncompressedBundle;
import net.discdd.model.UncompressedPayload;
import net.discdd.server.applicationdatamanager.ApplicationDataManager;
import net.discdd.server.bundlerouting.BundleRouting;
import net.discdd.server.bundlerouting.ServerWindowService;
import net.discdd.server.bundlesecurity.BundleSecurity;
import net.discdd.server.config.BundleServerConfig;
import net.discdd.utils.AckRecordUtils;
import net.discdd.utils.BundleUtils;
import net.discdd.utils.Constants;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.whispersystems.libsignal.InvalidKeyException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;
import static net.discdd.grpc.BundleSenderType.CLIENT;
import static net.discdd.grpc.BundleSenderType.TRANSPORT;

@Service
public class BundleTransmission {

    private static final Logger logger = Logger.getLogger(BundleTransmission.class.getName());
    public static final int WINDOW_LENGTH = 3;
    private final BundleServerConfig config;
    private final BundleSecurity bundleSecurity;
    private final ApplicationDataManager applicationDataManager;
    private final BundleRouting bundleRouting;
    private final ServerWindowService serverWindowService;
    private final ServerSecurity serverSecurity;
    SecureRandom secureRandom = new SecureRandom();

    public BundleTransmission(BundleSecurity bundleSecurity, ApplicationDataManager applicationDataManager,
                              BundleRouting bundleRouting, BundleServerConfig config,
                              ServerWindowService serverWindowService, ServerSecurity serverSecurity) {
        this.config = config;
        this.bundleSecurity = bundleSecurity;
        this.applicationDataManager = applicationDataManager;
        this.bundleRouting = bundleRouting;
        this.serverWindowService = serverWindowService;
        this.serverSecurity = serverSecurity;
    }

    public static String bundleSenderToString(BundleSender sender) {
        return sender.getType() + " : " + sender.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void processReceivedBundle(BundleSender sender, Bundle bundle) throws Exception {
        logger.log(INFO, "Processing received bundle: " + bundle.getSource().getName());
        Path bundleRecvProcDir = TRANSPORT == sender.getType() ?
                this.config.getBundleTransmission().getReceivedProcessingDirectory().resolve(sender.getId()) :
                this.config.getBundleTransmission().getReceivedProcessingDirectory();

        bundleRecvProcDir.toFile().mkdirs();

        UncompressedBundle uncompressedBundle = BundleUtils.extractBundle(bundle, bundleRecvProcDir);
        String clientId = "";
        String serverIdReceived = SecurityUtils.generateID(
                uncompressedBundle.getSource().toPath().resolve(SecurityUtils.SERVER_IDENTITY_KEY));
        if (!bundleSecurity.bundleServerIdMatchesCurrentServer(serverIdReceived)) {
            logger.log(WARNING, "Received bundle's serverIdentity didn't match with current server, " +
                    "ignoring bundle with bundleId: " + uncompressedBundle.getBundleId());
            return;
        }

        clientId = SecurityUtils.generateID(
                uncompressedBundle.getSource().toPath().resolve(SecurityUtils.CLIENT_IDENTITY_KEY));
        var counters = this.applicationDataManager.getBundleCountersForClient(clientId);

        var receivedBundleCounter =
                this.bundleSecurity.getCounterFromBundlePath(uncompressedBundle.getSource().toPath(),
                                                             BundleIDGenerator.UPSTREAM);

        if (receivedBundleCounter <= counters.lastReceivedBundleCounter) {
            logger.log(WARNING,
                       "[BundleTransmission] Skipping bundle " + bundle.getSource().getName() + " as it is outdated");
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
            this.bundleRouting.processClientMetaData(uncompressedPayload.getSource().getAbsolutePath(), sender.getId(),
                                                     clientId);
        } catch (ClientMetaDataFileException | SQLException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        this.applicationDataManager.processAcknowledgement(clientId, uncompressedPayload.getAckRecord().getBundleId());
        if (!uncompressedPayload.getADUs().isEmpty()) {
            this.applicationDataManager.storeReceivedADUs(clientId, uncompressedPayload.getBundleId(),
                                                          receivedBundleCounter, uncompressedPayload.getADUs());
        }
    }

    public void processReceivedBundles(BundleSender sender) {
        File receivedBundlesDirectory = this.config.getBundleTransmission().getBundleReceivedLocation().toFile();
        File[] files = receivedBundlesDirectory.listFiles();
        if (files != null) for (final File transportDir : files) {
            if (TRANSPORT == sender.getType() && !sender.getId().equals(transportDir.getName())) {
                continue;
            }
            if (TRANSPORT == sender.getType()) {
                List<String> reachableClients = new ArrayList<>();
                reachableClients = bundleRouting.getClientsForTransportId(sender.getId());
            }

            if (transportDir.isDirectory()) {
                for (final File bundleFile : transportDir.listFiles()) {
                    processBundleFile(bundleFile, sender);
                }
            } else if (transportDir.isFile()) {
                processBundleFile(transportDir, sender);
            }
        }
    }

    private void processBundleFile(File bundleFile, BundleSender sender) {
        Bundle bundle = new Bundle(bundleFile);
        try {
            this.processReceivedBundle(sender, bundle);
        } catch (Exception e) {
            logger.log(SEVERE,
                       "[BundleTransmission] Failed to process received bundle from: " + bundleSenderToString(sender),
                       e);
        } finally {
            try {
                FileUtils.delete(bundleFile);
                FileUtils.deleteDirectory(bundle.getSource());
            } catch (IOException e) {
                logger.log(SEVERE, "e");
            }
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

        if (this.serverWindowService.isWindowFull(clientId)) {
            return this.applicationDataManager.getBundleCountersForClient(clientId).lastSentBundleId;
        }

        var counts = this.applicationDataManager.getBundleCountersForClient(clientId);
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
        bundleSecurity.getIdentityPublicKey();
        var adus = applicationDataManager.fetchADUsToSend(0, clientId);
        var byteArrayOsForPayload = new ByteArrayOutputStream();
        BundleUtils.createBundlePayloadForAdus(adus, null, counts.lastReceivedBundleId, byteArrayOsForPayload);
        try (var bundleOutputStream = Files.newOutputStream(getPathForBundleToSend(encryptedBundleId),
                                                            StandardOpenOption.CREATE,
                                                            StandardOpenOption.TRUNCATE_EXISTING)) {
            BundleUtils.encryptPayloadAndCreateBundle(bytes -> serverSecurity.encrypt(clientId, bytes),
                                                      serverSecurity.getClientIdentityPublicKey(clientId),
                                                      serverSecurity.getClientBaseKey(clientId),
                                                      serverSecurity.getIdentityPublicKey().getPublicKey(),
                                                      encryptedBundleId, byteArrayOsForPayload.toByteArray(),
                                                      bundleOutputStream);
        }
        return encryptedBundleId;
    }

    public GetRecencyBlobResponse getRecencyBlob() throws InvalidKeyException {
        var blob = RecencyBlob.newBuilder().setVersion(0).setNonce(secureRandom.nextInt())
                .setBlobTimestamp(System.currentTimeMillis()).build();
        byte[] signature = this.bundleSecurity.signRecencyBlob(blob);
        byte[] publicKeyBytes = this.bundleSecurity.getIdentityPublicKey();
        return GetRecencyBlobResponse.newBuilder().setStatus(RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS)
                .setRecencyBlob(blob).setRecencyBlobSignature(ByteString.copyFrom(signature))
                .setServerPublicKey(ByteString.copyFrom(publicKeyBytes)).build();
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

    public BundlesToExchange inventoryBundlesForTransmission(BundleSender sender, Set<String> bundleIdsPresent) {
        List<String> clientIds = CLIENT == sender.getType() ? Collections.singletonList(sender.getId()) :
                this.bundleRouting.getClientsForTransportId(sender.getId());

        logger.log(SEVERE, "[BundleTransmission] Found " + clientIds.size() + " reachable from the sender: " +
                bundleSenderToString(sender));
        Set<String> deletionSet = new HashSet<>(bundleIdsPresent);
        List<String> bundlesToSend = new ArrayList<>();

        // get the latest
        Map<String, Set<String>> clientIdToBundleIds = new HashMap<>();

        for (String clientId : clientIds) {
            clientIdToBundleIds.put(clientId, new HashSet<>());
            logger.log(INFO, "[BT/inventBundle] Processing client " + clientId);
        }

        for (String clientId : clientIds) {
            try {
                var clientBundle = this.generateBundleForClient(clientId);
                bundlesToSend.add(clientBundle);
            } catch (InvalidClientIDException | GeneralSecurityException | InvalidKeyException | IOException e) {
                logger.log(SEVERE, "Failed to generate bundle for client " + clientId, e);
            }
        }
        bundlesToSend.forEach(deletionSet::remove);

        return new BundlesToExchange(bundlesToSend, bundleIdsPresent.stream().toList(), new ArrayList<>(deletionSet));
    }

    public record BundlesToExchange(List<String> bundlesToDownload, List<String> bundlesToUpload,
                                    List<String> bundlesToDelete) {}

}
