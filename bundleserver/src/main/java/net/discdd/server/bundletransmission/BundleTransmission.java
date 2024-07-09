package net.discdd.server.bundletransmission;

import net.discdd.bundlerouting.RoutingExceptions.ClientMetaDataFileException;
import net.discdd.bundlerouting.WindowUtils.WindowExceptions.ClientWindowNotFound;
import net.discdd.bundlesecurity.BundleIDGenerator;
import net.discdd.bundlesecurity.SecurityUtils;
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
import net.discdd.server.bundlesecurity.InvalidClientIDException;
import net.discdd.server.bundlesecurity.InvalidClientSessionException;
import net.discdd.server.config.BundleServerConfig;
import net.discdd.server.repository.LargestBundleIdReceivedRepository;
import net.discdd.utils.AckRecordUtils;
import net.discdd.utils.BundleUtils;
import net.discdd.utils.Constants;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.whispersystems.libsignal.InvalidKeyException;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

@Service
public class BundleTransmission {

    private static final Logger logger = Logger.getLogger(BundleTransmission.class.getName());
    private final BundleServerConfig config;
    private final BundleSecurity bundleSecurity;
    private final ApplicationDataManager applicationDataManager;
    private final BundleRouting bundleRouting;
    private final BundleUtils bundleUtils;
    private final ServerWindowService serverWindowService;
    private final int WINDOW_LENGTH = 3;

    public BundleTransmission(BundleSecurity bundleSecurity, ApplicationDataManager applicationDataManager,
                              BundleRouting bundleRouting,
                              LargestBundleIdReceivedRepository largestBundleIdReceivedRepository,
                              BundleServerConfig config, ServerWindowService serverWindowService) {
        this.bundleSecurity = bundleSecurity;
        this.applicationDataManager = applicationDataManager;
        this.config = config;
        this.bundleRouting = bundleRouting;
        this.serverWindowService = serverWindowService;
        this.bundleUtils = new BundleUtils();
    }

    @Transactional(rollbackFor = Exception.class)
    public void processReceivedBundle(String transportId, Bundle bundle) throws Exception {
        Path bundleRecvProcDir =
                this.config.getBundleTransmission().getReceivedProcessingDirectory().resolve(transportId);

        bundleRecvProcDir.toFile().mkdirs();

        UncompressedBundle uncompressedBundle = this.bundleUtils.extractBundle(bundle, bundleRecvProcDir);
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
        Optional<String> opt = this.applicationDataManager.getLargestRecvdBundleId(clientId);

        if (!opt.isEmpty() &&
                (this.bundleSecurity.isNewerBundle(uncompressedBundle.getSource().toPath(), opt.get()) >= 0)) {
            logger.log(WARNING,
                       "[BundleTransmission] Skipping bundle " + bundle.getSource().getName() + " as it is outdated");
            return;
        }

        Payload payload = this.bundleSecurity.decryptPayload(uncompressedBundle);
        if (payload == null) {
            throw new Exception("Payload is null");
        }

        UncompressedPayload uncompressedPayload =
                this.bundleUtils.extractPayload(payload, uncompressedBundle.getSource().toPath());

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
            this.bundleRouting.processClientMetaData(uncompressedPayload.getSource().getAbsolutePath(), transportId,
                                                     clientId);
        } catch (ClientMetaDataFileException | SQLException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        this.applicationDataManager.processAcknowledgement(clientId, uncompressedPayload.getAckRecord().getBundleId());
        if (!uncompressedPayload.getADUs().isEmpty()) {
            this.applicationDataManager.storeADUs(clientId, uncompressedPayload.getBundleId(),
                                                  uncompressedPayload.getADUs());
        }
    }

    public void processReceivedBundles(String transportId) {
        if (transportId == null) {
            return;
        }
        File receivedBundlesDirectory = this.config.getBundleTransmission().getBundleReceivedLocation().toFile();
        for (final File transportDir : receivedBundlesDirectory.listFiles()) {
            if (!transportId.equals(transportDir.getName())) {
                continue;
            }
            List<String> reachableClients = new ArrayList<>();
            try {
                reachableClients = bundleRouting.getClients(transportId);
            } catch (SQLException e1) {
                e1.printStackTrace();
            }
            for (final File bundleFile : transportDir.listFiles()) {
                Bundle bundle = new Bundle(bundleFile);
                try {
                    this.processReceivedBundle(transportId, bundle);
                } catch (Exception e) {
                    logger.log(SEVERE, "[BundleTransmission] Failed to process received bundle from transportId: " +
                            transportId + ", error: " + e.getMessage());
                } finally {
                    try {
                        FileUtils.delete(bundleFile);
                    } catch (IOException e) {
                        logger.log(SEVERE, "e");
                    }
                }
            }
        }
    }

    private String getAckRecordLocation(String clientId) {
        return this.config.getBundleTransmission().getToBeBundledDirectory()
                .resolve(Path.of(clientId, Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME)).toString();
    }

    private UncompressedPayload.Builder generatePayloadBuilder(String clientId) {
        List<ADU> ADUs = this.applicationDataManager.fetchADUs(0L, clientId);

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

    private String generateBundleId(String clientId) throws SQLException, InvalidClientIDException,
            GeneralSecurityException, InvalidKeyException {
        return this.serverWindowService.getCurrentbundleID(clientId);
    }

    private BundleTransferDTO generateBundleForTransmission(String transportId, String clientId,
                                                            Set<String> bundleIdsPresent) throws ClientWindowNotFound
            , SQLException, InvalidClientIDException, GeneralSecurityException, InvalidKeyException,
            InvalidClientSessionException, IOException {
        logger.log(INFO, "[BundleTransmission] Processing bundle generation request for client " + clientId);
        Set<String> deletionSet = new HashSet<>();
        List<BundleDTO> bundlesToSend = new ArrayList<>();

        Optional<UncompressedPayload.Builder> optional =
                this.applicationDataManager.getLastSentBundlePayloadBuilder(clientId);
        UncompressedPayload.Builder generatedPayloadBuilder = this.generatePayloadBuilder(clientId);

        Optional<UncompressedPayload> toSendOpt = Optional.empty();
        String bundleId = "";
        boolean isRetransmission = false;

        try {
            this.serverWindowService.addClient(clientId, this.WINDOW_LENGTH);
        } catch (Exception e) {
            logger.log(SEVERE, "[ServerWindow] INFO : Did not Add client " + clientId + " : " + e);
        }

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

        } else if (!generatedPayloadBuilder.getADUs()
                .isEmpty()) { // to ensure we never send a pure ack bundle i.e. a bundle with no ADUs
            if (optional.isEmpty()) { // no bundle ever sent
                bundleId = this.generateBundleId(clientId);
            } else {
                UncompressedPayload.Builder retxmnBundlePayloadBuilder = optional.get();
                if (optional.isPresent() && BundleUtils.doContentsMatch(generatedPayloadBuilder, optional.get())) {
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
            if (bundleIdsPresent.contains(toSendBundlePayload.getBundleId())) {
                deletionSet.addAll(bundleIdsPresent);
                deletionSet.remove(toSendBundlePayload.getBundleId());
                // We dont add toSend to bundlesToSend because the bundle is already on the transport.
            } else {
                if (!isRetransmission) {
                    this.applicationDataManager.notifyBundleGenerated(clientId, toSendBundlePayload);
                }
                this.bundleUtils.writeUncompressedPayload(toSendBundlePayload, this.config.getBundleTransmission()
                        .getUncompressedPayloadDirectory().toFile(), toSendBundlePayload.getBundleId());

                Payload payload = this.bundleUtils.compressPayload(toSendBundlePayload,
                                                                   this.config.getBundleTransmission()
                                                                           .getCompressedPayloadDirectory());
                UncompressedBundle uncompressedBundle = this.bundleSecurity.encryptPayload(clientId, payload,
                                                                                           this.config.getBundleTransmission()
                                                                                                   .getEncryptedPayloadDirectory());

                File toSendTxpDir =
                        this.config.getBundleTransmission().getToSendDirectory().resolve(transportId).toFile();
                toSendTxpDir.mkdirs();

                Bundle toSend = this.bundleUtils.compressBundle(uncompressedBundle, toSendTxpDir.toPath());
                bundlesToSend.add(new BundleDTO(bundleId, toSend));
                deletionSet.addAll(bundleIdsPresent);
            }
        }

        return new BundleTransferDTO(deletionSet, bundlesToSend);
    }

    public BundleTransferDTO generateBundlesForTransmission(String transportId, Set<String> bundleIdsPresent) throws SQLException, ClientWindowNotFound, InvalidClientIDException, GeneralSecurityException, InvalidClientSessionException, IOException, InvalidKeyException {
        List<String> clientIds = null;
        clientIds = this.bundleRouting.getClients(transportId);
        logger.log(SEVERE,
                   "[BundleTransmission] Found " + clientIds.size() + " reachable from the transport " + transportId);
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
            dtoForClient = this.generateBundleForTransmission(transportId, clientId, clientIdToBundleIds.get(clientId));
            deletionSet.addAll(dtoForClient.getDeletionSet());
            bundlesToSend.addAll(dtoForClient.getBundles());
        }
        return new BundleTransferDTO(deletionSet, bundlesToSend);
    }

    public List<File> getBundlesForTransmission(String transportId) {
        logger.log(INFO,
                   "[BundleTransmission] Inside getBundlesForTransmission method for transport id: " + transportId);
        List<File> bundles = new ArrayList<>();
        File recvTransportSubDir =
                this.config.getBundleTransmission().getToSendDirectory().resolve(transportId).toFile();
        File[] recvTransport = recvTransportSubDir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                return !file.isHidden();
            }
        });

        if (recvTransport == null || recvTransport.length == 0) {
            logger.log(INFO, "[BundleTransmission] No bundles to deliver through transport " + transportId);
            return bundles;
        }

        logger.log(INFO,
                   "[BundleTransmission] Found " + recvTransport.length + " bundles to deliver through transport " +
                           transportId);
        Collections.addAll(bundles, recvTransport);
        return bundles;
    }

    public void notifyBundleSent(BundleDTO bundleDTO) {
        logger.log(INFO, "[BundleTransmission] Inside method notifyBundleSent");
        //      try {
        //        FileUtils.delete(bundle.getSource());
        //      } catch (IOException e) {
        //        e.printStackTrace();
        //      }
        // TODO: Commented out for the moment.
        String clientId = "";
        try {
            clientId = BundleIDGenerator.getClientIDFromBundleID(bundleDTO.getBundleId(), BundleIDGenerator.DOWNSTREAM);
            this.serverWindowService.updateClientWindow(clientId, bundleDTO.getBundleId());
            logger.log(INFO, "[BundleTransmission] Updated client window for client " + clientId + " with bundle id: " +
                    bundleDTO.getBundleId());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
