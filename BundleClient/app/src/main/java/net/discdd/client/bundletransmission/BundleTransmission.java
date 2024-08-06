package net.discdd.client.bundletransmission;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;

import net.discdd.bundlerouting.RoutingExceptions;
import net.discdd.bundlerouting.WindowUtils.WindowExceptions;
import net.discdd.bundlerouting.service.FileServiceImpl;
import net.discdd.bundlesecurity.BundleIDGenerator;
import net.discdd.bundletransport.service.BundleSender;
import net.discdd.client.applicationdatamanager.ApplicationDataManager;
import net.discdd.client.bundlerouting.ClientBundleGenerator;
import net.discdd.client.bundlerouting.ClientRouting;
import net.discdd.client.bundlesecurity.BundleSecurity;
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

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.whispersystems.libsignal.DuplicateMessageException;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.LegacyMessageException;
import org.whispersystems.libsignal.NoSessionException;

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
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

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

    public BundleTransmission(Path rootFolder) throws WindowExceptions.InvalidLength, WindowExceptions.BufferOverflow
            , IOException, InvalidKeyException, RoutingExceptions.ClientMetaDataFileException,
            NoSuchAlgorithmException {
        this.ROOT_DIR = rootFolder;
        this.bundleSecurity = new BundleSecurity(this.ROOT_DIR);
        this.applicationDataManager = new ApplicationDataManager(this.ROOT_DIR);

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
        logger.log(INFO, "Updating client routing metadata for sender:  " + FileServiceImpl.bundleSenderToString(sender));
        clientRouting.updateMetaData(sender.getId());

        String bundleId = payload.getBundleId();

        ClientBundleGenerator clientBundleGenerator = this.bundleSecurity.getClientBundleGenerator();
        boolean isLatestBundleId = (!StringUtils.isBlank(largestBundleIdReceived) &&
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
            FileUtils.deleteQuietly(bundle.getSource());
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

    public void notifyBundleSent(BundleDTO bundle) {
        FileUtils.deleteQuietly(bundle.getBundle().getSource());
    }

    public BundleSecurity getBundleSecurity() {
        return this.bundleSecurity;
    }

    public ClientRouting getClientRouting() {
        return clientRouting;
    }

}
