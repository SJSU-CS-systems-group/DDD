package net.discdd.server.bundletransmission;

import com.google.protobuf.ByteString;
import net.discdd.bundlerouting.RoutingExceptions.ClientMetaDataFileException;
import net.discdd.bundlerouting.WindowUtils.WindowExceptions.ClientWindowNotFound;
import net.discdd.bundlesecurity.InvalidClientIDException;
import net.discdd.bundlesecurity.InvalidClientSessionException;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.grpc.BundleSender;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.RecencyBlob;
import net.discdd.grpc.RecencyBlobStatus;
import net.discdd.model.ADU;
import net.discdd.model.Acknowledgement;
import net.discdd.model.Bundle;
import net.discdd.model.BundleDTO;
import net.discdd.model.BundleTransferDTO;
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private static final int WINDOW_LENGTH = 3;
    private final BundleServerConfig config;
    private final BundleSecurity bundleSecurity;
    private final ApplicationDataManager applicationDataManager;
    private final BundleRouting bundleRouting;
    private final BundleUtils bundleUtils;
    private final ServerWindowService serverWindowService;
    SecureRandom secureRandom = new SecureRandom();

    public BundleTransmission(BundleSecurity bundleSecurity, ApplicationDataManager applicationDataManager,
                              BundleRouting bundleRouting, BundleServerConfig config,
                              ServerWindowService serverWindowService) {
        this.bundleSecurity = bundleSecurity;
        this.applicationDataManager = applicationDataManager;
        this.config = config;
        this.bundleRouting = bundleRouting;
        this.serverWindowService = serverWindowService;
        this.bundleUtils = new BundleUtils();
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
        var largestBundleId = this.applicationDataManager.getLargestRecvdBundleId(clientId);

        if (largestBundleId != null &&
                (this.bundleSecurity.isNewerBundle(uncompressedBundle.getSource().toPath(), largestBundleId) < 0)) {
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

        File ackRecordFile = new File(this.getAckRecordLocation(clientId));
        ackRecordFile.getParentFile().mkdirs();
        try {
            ackRecordFile.createNewFile();
        } catch (IOException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }

        AckRecordUtils.writeAckRecordToFile(new Acknowledgement(uncompressedPayload.getBundleId()), ackRecordFile);

        Path clientAckSubDirectory = this.config.getBundleTransmission().getToBeBundledDirectory().resolve(clientId);

        if (!clientAckSubDirectory.toFile().exists()) {
            clientAckSubDirectory.toFile().mkdirs();
        }

        File ackFile = clientAckSubDirectory.resolve(Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME).toFile();
        try {
            if (!ackFile.exists()) {
                ackFile.createNewFile();
            }
            AckRecordUtils.writeAckRecordToFile(new Acknowledgement(uncompressedPayload.getBundleId()), ackFile);
        } catch (IOException e) {
            e.printStackTrace();
        }

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
                                                          uncompressedPayload.getADUs());
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

    private String getAckRecordLocation(String clientId) {
        return this.config.getBundleTransmission().getToBeBundledDirectory()
                .resolve(Path.of(clientId, Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME)).toString();
    }

    private UncompressedPayload.Builder generatePayloadBuilder(String clientId) throws IOException {
        List<ADU> ADUs = this.applicationDataManager.fetchADUsToSend(0L, clientId);

        UncompressedPayload.Builder builder = new UncompressedPayload.Builder();

        File ackFile = new File(this.getAckRecordLocation(clientId));
        new File(ackFile.getParent()).mkdirs();

        Acknowledgement ackRecord = null;
        if (ackFile.exists()) {
            ackRecord = AckRecordUtils.readAckRecordFromFile(ackFile);
        } else {
            ackRecord = new Acknowledgement("HB");
            AckRecordUtils.writeAckRecordToFile(ackRecord, ackFile);
        }

        builder.setAckRecord(ackRecord);

        long totalSize = ackRecord.getSize();

        List<ADU> adusToPack = new ArrayList<>();
        for (ADU adu : ADUs) {
            if (adu.getSize() + totalSize > this.config.getBundleTransmission().getBundleSizeLimit()) {
                break;
            }
            totalSize += adu.getSize();
            adusToPack.add(adu);
        }
        builder.setADUs(adusToPack);

        return builder;
    }

    public String generateBundleId(String clientId) throws SQLException, InvalidClientIDException,
            GeneralSecurityException, InvalidKeyException {
        return this.serverWindowService.getCurrentBundleID(clientId);
    }

    public long getCounterFromEncryptedBundleId(String encryptedBundleId, String clientId, boolean direction) throws InvalidClientIDException, GeneralSecurityException, InvalidKeyException {
        return this.serverWindowService.getCounterFromBundleID(encryptedBundleId, clientId, direction);
    }

    public BundleTransferDTO generateBundleForTransmission(BundleSender sender, String clientId,
                                                           Set<String> bundleIdsPresent) throws ClientWindowNotFound,
            InvalidClientIDException, GeneralSecurityException, InvalidKeyException, InvalidClientSessionException,
            IOException, SQLException {
        logger.log(INFO, "[BundleTransmission] Processing bundle generation request for client " + clientId);
        Set<String> deletionSet = new HashSet<>();
        List<BundleDTO> bundlesToSend = new ArrayList<>();

        Optional<UncompressedPayload.Builder> optional =
                this.applicationDataManager.getLastSentBundlePayloadBuilder(clientId);
        UncompressedPayload.Builder generatedPayloadBuilder = this.generatePayloadBuilder(clientId);

        Optional<UncompressedPayload> toSendOpt = Optional.empty();
        String bundleId = "";
        boolean isRetransmission = false;

        this.serverWindowService.addClient(clientId, WINDOW_LENGTH);

        boolean isSenderWindowFull = this.serverWindowService.isClientWindowFull(clientId);

        if (isSenderWindowFull) {
            logger.log(INFO, "[BundleTransmission] Server's sender window is full for the client " + clientId);
            UncompressedPayload.Builder retxmnBundlePayloadBuilder =
                    optional.get(); // there was definitely a bundle sent previously if sender window is full

            bundleId = retxmnBundlePayloadBuilder.getBundleId();
            retxmnBundlePayloadBuilder.setSource(
                    this.config.getBundleTransmission().getUncompressedPayloadDirectory().resolve(bundleId).toFile());
            UncompressedPayload toSend = retxmnBundlePayloadBuilder.build();
            toSendOpt = Optional.of(toSend);
            isRetransmission = true;

        } else /* if (!generatedPayloadBuilder.getADUs()
                .isEmpty()) */ { // to ensure we never send a pure ack bundle i.e. a bundle with no ADUs
            if (optional.isEmpty()) { // no bundle ever sent
                bundleId = this.generateBundleId(clientId);
            } else {
                UncompressedPayload.Builder retxmnBundlePayloadBuilder = optional.get();
                if (BundleUtils.doContentsMatch(generatedPayloadBuilder, optional.get())) {
                    bundleId = retxmnBundlePayloadBuilder.getBundleId();
                    isRetransmission = true;
                } else { // new data to send
                    bundleId = this.generateBundleId(clientId);
                }
            }

            generatedPayloadBuilder.setBundleId(bundleId);
            generatedPayloadBuilder.setSource(
                    this.config.getBundleTransmission().getUncompressedPayloadDirectory().resolve(bundleId).toFile());
            UncompressedPayload toSend = generatedPayloadBuilder.build();
            toSendOpt = Optional.of(toSend);
        }

        if (toSendOpt.isPresent()) {
            UncompressedPayload toSendBundlePayload = toSendOpt.get();
            if (TRANSPORT == sender.getType() && bundleIdsPresent.contains(toSendBundlePayload.getBundleId())) {
                deletionSet.addAll(bundleIdsPresent);
                deletionSet.remove(toSendBundlePayload.getBundleId());
                // We dont add toSend to bundlesToSend because the bundle is already on the transport.
            } else {
                if (!isRetransmission) {
                    this.applicationDataManager.notifyBundleGenerated(clientId, toSendBundlePayload);
                }
                BundleUtils.writeUncompressedPayload(toSendBundlePayload, this.config.getBundleTransmission()
                        .getUncompressedPayloadDirectory().toFile(), toSendBundlePayload.getBundleId());

                Payload payload = BundleUtils.compressPayload(toSendBundlePayload, this.config.getBundleTransmission()
                        .getCompressedPayloadDirectory());
                UncompressedBundle uncompressedBundle = this.bundleSecurity.encryptPayload(clientId, payload,
                                                                                           this.config.getBundleTransmission()
                                                                                                   .getEncryptedPayloadDirectory());

                File toSendTxpDir = this.config.getBundleTransmission().getToSendDirectory().resolve(clientId).toFile();
                toSendTxpDir.mkdirs();

                Bundle toSend = BundleUtils.compressBundle(uncompressedBundle, toSendTxpDir.toPath());
                bundlesToSend.add(new BundleDTO(bundleId, toSend));
                if (bundleIdsPresent != null) deletionSet.addAll(bundleIdsPresent);
            }
        }

        return new BundleTransferDTO(deletionSet, bundlesToSend);
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

    public BundlesToExchange generateBundlesForTransmission(BundleSender sender, Set<String> bundleIdsPresent) throws SQLException, ClientWindowNotFound, InvalidClientIDException, GeneralSecurityException, InvalidClientSessionException, IOException, InvalidKeyException {
        List<String> clientIds = CLIENT == sender.getType() ? Collections.singletonList(sender.getId()) :
                this.bundleRouting.getClientsForTransportId(sender.getId());

        logger.log(SEVERE, "[BundleTransmission] Found " + clientIds.size() + " reachable from the sender: " +
                bundleSenderToString(sender));
        Set<String> deletionSet = new HashSet<>();
        List<BundleDTO> bundlesToSend = new ArrayList<>();
        Map<String, Set<String>> clientIdToBundleIds = new HashMap<>();

        for (String clientId : clientIds) {
            clientIdToBundleIds.put(clientId, new HashSet<>());
        }

        for (String bundleId : bundleIdsPresent) {
            String clientId = this.applicationDataManager.getClientIdFromSentBundleId(bundleId);
            Set<String> bundleIds = clientIdToBundleIds.getOrDefault(clientId, new HashSet<>());
            bundleIds.add(bundleId);
            clientIdToBundleIds.put(clientId, bundleIds);
        }
        for (String clientId : clientIds) {
            BundleTransferDTO dtoForClient;
            dtoForClient = this.generateBundleForTransmission(sender, clientId, clientIdToBundleIds.get(clientId));
            deletionSet.addAll(dtoForClient.getDeletionSet());
            bundlesToSend.addAll(dtoForClient.getBundles());
        }
        return new BundlesToExchange(bundlesToSend.stream().map(BundleDTO::getBundleId).toList(),
                                     bundlesToSend.stream().map(BundleDTO::getBundleId).toList(),
                                     new ArrayList<>(deletionSet));
    }

    public List<File> getBundlesForTransmission(BundleSender sender) {
        logger.log(INFO,
                   "[BundleTransmission] Inside getBundlesForTransmission method for " + bundleSenderToString(sender));
        List<File> bundles = new ArrayList<>();
        List<String> clientIds = new ArrayList<>();
        if (CLIENT == sender.getType()) {
            clientIds.add(sender.getId());
        } else {
            clientIds.addAll(bundleRouting.getClientsForTransportId(sender.getId()));
        }

        for (String clientId : clientIds) {
            File recvTransportSubDir =
                    this.config.getBundleTransmission().getToSendDirectory().resolve(clientId).toFile();

            File[] bundleFilesForClientId = recvTransportSubDir.listFiles(file -> !file.isHidden());

            if (null != bundleFilesForClientId) bundles.addAll(List.of(Objects.requireNonNull(bundleFilesForClientId)));
        }

        logger.log(INFO, "[BundleTransmission] Found " + bundles.size() + " bundles to deliver through " +
                bundleSenderToString(sender));
        return bundles;
    }

    public record BundlesToExchange(List<String> bundlesToDownload, List<String> bundlesToUpload,
                                    List<String> bundlesToDelete) {}

}
