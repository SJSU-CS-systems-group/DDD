package net.discdd.client.bundletransmission;

import lombok.Getter;
import net.discdd.bundlerouting.RoutingExceptions;
import net.discdd.bundlerouting.WindowUtils.WindowExceptions;
import net.discdd.bundlesecurity.BundleIDGenerator;
import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.client.applicationdatamanager.ApplicationDataManager;
import net.discdd.client.bundlerouting.ClientBundleGenerator;
import net.discdd.client.bundlerouting.ClientRouting;
import net.discdd.client.bundlesecurity.BundleSecurity;
import net.discdd.grpc.BundleSender;
import net.discdd.grpc.GetRecencyBlobResponse;
import net.discdd.grpc.RecencyBlobStatus;
import net.discdd.model.ADU;
import net.discdd.model.Acknowledgement;
import net.discdd.model.Bundle;
import net.discdd.model.BundleDTO;
import net.discdd.model.Payload;
import net.discdd.model.UncompressedBundle;
import net.discdd.model.UncompressedPayload;
import net.discdd.utils.AckRecordUtils;
import net.discdd.utils.BundleUtils;
import net.discdd.utils.Constants;
import net.discdd.utils.FileUtils;
import org.whispersystems.libsignal.DuplicateMessageException;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.LegacyMessageException;
import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.libsignal.ecc.Curve;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;

public class BundleTransmission {
    private static final Logger logger = Logger.getLogger(BundleTransmission.class.getName());

    /* Bundle generation directory */
    private static final String BUNDLE_GENERATION_DIRECTORY = "BundleTransmission/bundle-generation";
    private static final String TO_BE_BUNDLED_DIRECTORY = "to-be-bundled";
    private static final String TO_SEND_DIRECTORY = "to-send";
    private static final String UNCOMPRESSED_PAYLOAD = "uncompressed-payload";
    private static final String COMPRESSED_PAYLOAD = "compressed-payload";
    private static final String ENCRYPTED_PAYLOAD = "encrypted-payload";
    private static final String RECEIVED_PROCESSING = "received-processing";
    private static final String LARGEST_BUNDLE_ID_RECEIVED = "Shared/DB/LARGEST_BUNDLE_ID_RECEIVED.txt";
    private final BundleSecurity bundleSecurity;
    private final ApplicationDataManager applicationDataManager;
    private final long BUNDLE_SIZE_LIMIT = 1000000000L;

    final private Path ROOT_DIR;
    private final Path ackRecordPath;

    private ClientRouting clientRouting;

    public BundleTransmission(Path rootFolder, Consumer<ADU> aduConsumer) throws WindowExceptions.BufferOverflow,
            IOException, InvalidKeyException, RoutingExceptions.ClientMetaDataFileException, NoSuchAlgorithmException {
        this.ROOT_DIR = rootFolder;
        this.bundleSecurity = new BundleSecurity(this.ROOT_DIR);
        this.applicationDataManager = new ApplicationDataManager(this.ROOT_DIR, aduConsumer);

        this.clientRouting = ClientRouting.initializeInstance(rootFolder);

        var bundleGenerationDir = ROOT_DIR.resolve(BUNDLE_GENERATION_DIRECTORY);
        var toBeBundledDir = ROOT_DIR.resolve(TO_BE_BUNDLED_DIRECTORY);
        ackRecordPath = toBeBundledDir.resolve(Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME);
        net.discdd.utils.FileUtils.createFileWithDefaultIfNeeded(ackRecordPath, "HB".getBytes());
        var tosendDir = bundleGenerationDir.resolve(TO_SEND_DIRECTORY);
        tosendDir.toFile().mkdirs();

        var uncompressedPayloadDir = bundleGenerationDir.resolve(UNCOMPRESSED_PAYLOAD);
        uncompressedPayloadDir.toFile().mkdirs();
        var compressedPayloadDir = bundleGenerationDir.resolve(COMPRESSED_PAYLOAD);
        compressedPayloadDir.toFile().mkdirs();
        var encryptedPayloadDir = bundleGenerationDir.resolve(ENCRYPTED_PAYLOAD);
        encryptedPayloadDir.toFile().mkdirs();
        var receivedProcDir = bundleGenerationDir.resolve(RECEIVED_PROCESSING);
        receivedProcDir.toFile().mkdirs();
    }

    public void registerBundleId(String bundleId) throws IOException {
        try (BufferedWriter bufferedWriter = new BufferedWriter(
                new FileWriter(this.ROOT_DIR.resolve(LARGEST_BUNDLE_ID_RECEIVED).toFile()))) {
            bufferedWriter.write(bundleId);
        }
        System.out.println("[BS] Registered bundle identifier: " + bundleId);
    }

    private String getLargestBundleIdReceived() throws IOException {
        String bundleId = "";
        try (BufferedReader bufferedReader = new BufferedReader(
                new FileReader(this.ROOT_DIR.resolve(LARGEST_BUNDLE_ID_RECEIVED).toFile()))) {
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                bundleId = line.trim();
            }
        }
        System.out.println("[BS] Largest bundle id received so far: " + bundleId);
        return bundleId.trim();
    }

    private void processReceivedBundle(BundleSender sender, Bundle bundle) throws IOException,
            RoutingExceptions.ClientMetaDataFileException, NoSessionException, InvalidMessageException,
            DuplicateMessageException, LegacyMessageException, InvalidKeyException, GeneralSecurityException {
        String largestBundleIdReceived = this.getLargestBundleIdReceived();
        UncompressedBundle uncompressedBundle = BundleUtils.extractBundle(bundle, this.ROOT_DIR.resolve(
                Paths.get(BUNDLE_GENERATION_DIRECTORY, RECEIVED_PROCESSING)));
        Payload payload = this.bundleSecurity.decryptPayload(uncompressedBundle);
        logger.log(INFO, "Updating client routing metadata for sender:  " + bundleSenderToString(sender));
        clientRouting.updateMetaData(sender.getId());

        String bundleId = payload.getBundleId();

        ClientBundleGenerator clientBundleGenerator = this.bundleSecurity.getClientBundleGenerator();
        boolean isLatestBundleId = (!largestBundleIdReceived.isEmpty() &&
                clientBundleGenerator.compareBundleIDs(bundleId, Long.parseLong(largestBundleIdReceived),
                                                       BundleIDGenerator.DOWNSTREAM) == 1);

        if (isLatestBundleId) {
            return;
        }
        UncompressedPayload uncompressedPayload =
                BundleUtils.extractPayload(payload, uncompressedBundle.getSource().toPath());

        AckRecordUtils.writeAckRecordToFile(new Acknowledgement(bundleId), ackRecordPath.toFile());
        this.registerBundleId(bundleId);

        String ackedBundleId = uncompressedPayload.getAckRecord().getBundleId();

        this.applicationDataManager.processAcknowledgement(ackedBundleId);
        this.applicationDataManager.storeReceivedADUs(null, null, uncompressedPayload.getADUs());

    }

    public static String bundleSenderToString(BundleSender sender) {
        return sender.getType() + " : " + sender.getId();
    }

    public void processReceivedBundles(BundleSender sender, String bundlesLocation) throws WindowExceptions.BufferOverflow, IOException, InvalidKeyException, RoutingExceptions.ClientMetaDataFileException, NoSessionException, InvalidMessageException, DuplicateMessageException, LegacyMessageException, GeneralSecurityException {
        File bundleStorageDirectory = new File(bundlesLocation);
        logger.log(FINE, "inside receives" + bundlesLocation);
        if (bundleStorageDirectory.listFiles() == null || bundleStorageDirectory.listFiles().length == 0) {
            logger.log(INFO, "No Bundle received");
            return;
        }
        for (final File bundleFile : bundleStorageDirectory.listFiles()) {
            Bundle bundle = new Bundle(bundleFile);
            logger.log(INFO, "Processing: " + bundle.getSource().getName());
            this.processReceivedBundle(sender, bundle);
            logger.log(INFO, "Deleting Directory");
            FileUtils.recursiveDelete(bundle.getSource().toPath());
            logger.log(INFO, "Deleted Directory");
        }
        String largestBundleId = getLargestBundleIdReceived();
        this.bundleSecurity.registerLargestBundleIdReceived(largestBundleId);
    }

    private UncompressedPayload.Builder generateBundleBuilder() throws IOException {

        UncompressedPayload.Builder builder = new UncompressedPayload.Builder();
        Acknowledgement ackRecord = AckRecordUtils.readAckRecordFromFile(ackRecordPath.toFile());
        builder.setAckRecord(ackRecord);

        List<ADU> ADUs = this.applicationDataManager.fetchADUsToSend(ackRecord.getSize(), null);

        logger.log(INFO, "[UncompressedPayloadBuilder] ADUs: " + ADUs.size());

        builder.setADUs(ADUs);

        return builder;
    }

    private BundleDTO generateNewBundle(Path targetDir) throws RoutingExceptions.ClientMetaDataFileException,
            IOException, InvalidKeyException, GeneralSecurityException {
        UncompressedPayload.Builder builder = this.generateBundleBuilder();
        String bundleId = this.bundleSecurity.generateNewBundleId();

        return generateNewBundle(builder, targetDir, bundleId);
    }

    private BundleDTO generateNewBundle(UncompressedPayload.Builder builder, Path targetDir, String bundleId) throws RoutingExceptions.ClientMetaDataFileException, IOException, InvalidKeyException {
        builder.setBundleId(bundleId);
        File uncompressedBundleFile =
                this.ROOT_DIR.resolve(Paths.get(BUNDLE_GENERATION_DIRECTORY, UNCOMPRESSED_PAYLOAD)).toFile();
        builder.setSource(new File(uncompressedBundleFile, bundleId));
        UncompressedPayload toSendBundlePayload = builder.build();
        BundleUtils.writeUncompressedPayload(toSendBundlePayload, uncompressedBundleFile, bundleId);
        logger.log(INFO, "Placing routing.metadata in " + toSendBundlePayload.getSource().getAbsolutePath());
        clientRouting.bundleMetaData(toSendBundlePayload.getSource().toPath());

        Payload payload = BundleUtils.compressPayload(toSendBundlePayload, this.ROOT_DIR.resolve(
                Paths.get(BUNDLE_GENERATION_DIRECTORY, COMPRESSED_PAYLOAD)));
        UncompressedBundle uncompressedBundle = this.bundleSecurity.encryptPayload(payload, this.ROOT_DIR.resolve(
                Paths.get(BUNDLE_GENERATION_DIRECTORY, ENCRYPTED_PAYLOAD)));

        Bundle toSend = BundleUtils.compressBundle(uncompressedBundle, targetDir);
        this.applicationDataManager.notifyBundleSent(toSendBundlePayload);
        System.out.println("[BT] Generated new bundle for transmission with bundle id: " + bundleId);
        return new BundleDTO(bundleId, toSend);
    }

    public BundleDTO generateBundleForTransmission() throws RoutingExceptions.ClientMetaDataFileException,
            IOException, InvalidKeyException, GeneralSecurityException {
        logger.log(FINE, "Started process of generating bundle");
        Path toSendDir = this.ROOT_DIR.resolve(Paths.get(BUNDLE_GENERATION_DIRECTORY, TO_SEND_DIRECTORY));

        BundleDTO toSend = null;
        Optional<UncompressedPayload.Builder> optional = this.applicationDataManager.getLastSentBundleBuilder();

        // check if it's first bundle generation
        if (!optional.isPresent()) {
            toSend = this.generateNewBundle(toSendDir);
        } else {
            UncompressedPayload.Builder lastSentBundleBuilder = optional.get();
            UncompressedPayload.Builder unprocessedPayloadBuilder = this.generateBundleBuilder();

            String bundleId = "";
            // compare if last sent bundle is same as bundle generated now
            if (BundleUtils.doContentsMatch(unprocessedPayloadBuilder, lastSentBundleBuilder)) {
                bundleId = lastSentBundleBuilder.getBundleId();
                System.out.println("Retransmitting bundle");
            } else {
                bundleId = this.bundleSecurity.generateNewBundleId();
            }
            toSend = this.generateNewBundle(unprocessedPayloadBuilder, toSendDir, bundleId);
        }

        logger.log(INFO, "sending bundle with id: " + toSend.getBundleId());
        return toSend;
    }

    /**
     * Used to track in memory recently seen transports.
     * All times are in milliseconds since epoch.
     */
    @Getter
    public static class RecentTransport {
        /** from WifiP2pDevice.deviceAddress */
        private String deviceAddress;
        /* @param deviceName from WifiP2pDevice.deviceName */
        private String deviceName;
        /* @param lastExchange time of last bundle exchange */
        private long lastExchange;
        /* @param lastSeen time of last device discovery */
        private long lastSeen;
        /* @param recencyTime time from the last recencyBlob received */
        private long recencyTime;
        private RecentTransport(String deviceAddress) {
            this.deviceAddress = deviceAddress;
        }
    }

    final private HashMap<String, RecentTransport> recentTransports = new HashMap();

    public RecentTransport[] getRecentTransports() {
        synchronized (recentTransports) {
            return recentTransports.values().toArray(new RecentTransport[0]);
        }
    }

    public RecentTransport getRecentTransport(String deviceAddress) {
        synchronized (recentTransports) {
            return recentTransports.get(deviceAddress);
        }
    }

    public void discovered(String deviceAddress, String deviceName) {
        synchronized (recentTransports) {
            RecentTransport recentTransport = recentTransports.computeIfAbsent(deviceAddress, RecentTransport::new);
            recentTransport.deviceName = deviceName;
            recentTransport.lastSeen = System.currentTimeMillis();
        }
    }

    public void exchangedWithTransport(String deviceAddress) {
        synchronized (recentTransports) {
            RecentTransport recentTransport = recentTransports.computeIfAbsent(deviceAddress, RecentTransport::new);
            var now = System.currentTimeMillis();
            recentTransport.lastExchange = now;
            recentTransport.lastSeen = now;
        }
    }

    public void processRecencyBlob(String deviceAddress, GetRecencyBlobResponse recencyBlobResponse) throws IOException, InvalidKeyException {
        // first make sure the data is valid
        if (recencyBlobResponse.getStatus() != RecencyBlobStatus.RECENCY_BLOB_STATUS_SUCCESS) {
            throw new IOException("Recency request failed");
        }
        var recencyBlob = recencyBlobResponse.getRecencyBlob();
        // we will allow a 1 minute clock skew
        if (recencyBlob.getBlobTimestamp() > System.currentTimeMillis() + 60*1000) {
            throw new IOException("Recency blob timestamp is in the future");
        }
        var receivedServerPublicKey = Curve.decodePoint(recencyBlobResponse.getServerPublicKey().toByteArray(), 0);
        if (!bundleSecurity.getClientSecurity().getServerPublicKey().equals(receivedServerPublicKey)) {
            throw new IOException("Recency blob signed by unknown server");
        }
        if (!SecurityUtils.verifySignatureRaw(recencyBlob.toByteArray(),
                                              receivedServerPublicKey,
                                              recencyBlobResponse.getRecencyBlobSignature().toByteArray())) {
            throw new IOException("Recency blob signature verification failed");
        }
        synchronized (recentTransports) {
            RecentTransport recentTransport = recentTransports.computeIfAbsent(deviceAddress, RecentTransport::new);
            recentTransport.recencyTime = recencyBlob.getBlobTimestamp();
        }
    }

    public void notifyBundleSent(BundleDTO bundle) {
        FileUtils.recursiveDelete(bundle.getBundle().getSource().toPath());
    }

    public BundleSecurity getBundleSecurity() {
        return this.bundleSecurity;
    }

    public ClientRouting getClientRouting() {
        return clientRouting;
    }

}
